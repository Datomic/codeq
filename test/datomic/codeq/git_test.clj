(ns datomic.codeq.git-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [datomic.codeq.git :as git])
  (:import [org.eclipse.jgit.api Git]
           [org.eclipse.jgit.lib Repository]
           [java.io File]))

(defn- temp-dir ^File []
  (let [d (File/createTempFile "codeq-git-test" "")]
    (.delete d) (.mkdirs d) d))

(defn- delete-recursively [^File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively c)))
  (.delete f))

(defn with-temp-repo
  "Calls (f dir git repo) with a fresh initialized repo; cleans up after."
  [f]
  (let [dir (temp-dir)
        git (.. (Git/init) (setDirectory dir) (call))
        repo (.getRepository git)]
    (try (f dir git repo)
         (finally (.close git) (delete-recursively dir)))))

(defn commit-file!
  "Writes fname=content, stages, commits with a deterministic author. Returns RevCommit."
  [^Git git ^File dir ^String fname ^String content ^String msg]
  (spit (io/file dir fname) content)
  (.. git add (addFilepattern fname) (call))
  (.. git commit
      (setMessage msg)
      (setAuthor "Ada Lovelace" "ada@example.com")
      (setCommitter "Ada Lovelace" "ada@example.com")
      (call)))

(deftest blob-text-round-trips
  (testing "blob-text returns the file content for a committed blob"
    (with-temp-repo
      (fn [dir git ^Repository repo]
        (let [rc (commit-file! git dir "hello.txt" "hello world\n" "add hello")
              tree-sha (.name (.getTree rc))
              ;; find the blob sha via JGit TreeWalk in the test
              tw (doto (org.eclipse.jgit.treewalk.TreeWalk. repo)
                   (.addTree (org.eclipse.jgit.lib.ObjectId/fromString tree-sha))
                   (.setRecursive true))
              _ (.next tw)
              blob-sha (.name (.getObjectId tw 0))]
          (.close tw)
          (is (= "hello world\n" (git/blob-text repo blob-sha))))))))

(deftest commit-info-parses-fields
  (testing "commit-info returns author email, message, tree, and parents"
    (with-temp-repo
      (fn [dir git ^Repository repo]
        (let [c1 (commit-file! git dir "a.txt" "one\n" "first")
              c2 (commit-file! git dir "b.txt" "two\n" "second")
              info1 (git/commit-info repo (.name c1))
              info2 (git/commit-info repo (.name c2))]
          (is (= (.name c1) (:sha info1)))
          (is (= "first" (:msg info1)))
          (is (= "ada@example.com" (:author info1)))
          (is (= "ada@example.com" (:committer info1)))
          (is (instance? java.util.Date (:authored info1)))
          (is (nil? (:parents info1)))
          (is (= [(.name c1)] (vec (:parents info2)))))))))

(deftest commit-shas-orders-parents-first
  (testing "commit-shas returns commits parents-before-children"
    (with-temp-repo
      (fn [dir git ^Repository repo]
        (let [c1 (commit-file! git dir "a.txt" "one\n" "first")
              c2 (commit-file! git dir "a.txt" "one\ntwo\n" "second")
              shas (mapv first (git/commit-shas repo nil))]
          (is (= [(.name c1) (.name c2)] shas)))))))

(deftest repo-uri-reads-origin
  (testing "repo-uri returns [uri name] from remote.origin.url"
    (with-temp-repo
      (fn [dir git ^Repository repo]
        (let [cfg (.getConfig repo)]
          (.setString cfg "remote" "origin" "url" "git@github.com:devn/codeq.git")
          (.save cfg))
        (is (= ["git@github.com:devn/codeq.git" "codeq"] (git/repo-uri repo)))))))

(deftest tree-entries-lists-direct-children
  (testing "tree-entries returns [sha type filename] for top-level entries"
    (with-temp-repo
      (fn [dir git ^Repository repo]
        (.mkdirs (clojure.java.io/file dir "sub"))
        (spit (clojure.java.io/file dir "sub" "nested.txt") "n\n")
        (let [rc (commit-file! git dir "top.txt" "t\n" "mixed tree")
              ;; also stage the nested dir
              _ (.. git add (addFilepattern "sub") (call))
              rc2 (.. git commit (setMessage "with sub")
                      (setAuthor "Ada Lovelace" "ada@example.com")
                      (setCommitter "Ada Lovelace" "ada@example.com") (call))
              entries (git/tree-entries repo (.name (.getTree rc2)))
              by-name (into {} (map (fn [[sha type fname]] [fname [type]]) entries))]
          (is (= [:blob] (by-name "top.txt")))
          (is (= [:tree] (by-name "sub")))
          (is (every? #(= 40 (count (first %))) entries)))))))
