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
