(ns datomic.codeq.core-test
  (:use clojure.test
        datomic.codeq.core
        datomic.codeq.util
        datomic.codeq.test-datomic-util
        datomic.codeq.test-git-util)
  (:require [datomic.codeq.git :as git]
            [datomic.api :as d]
            [clojure.java.io :as io]))


(def ^:dynamic *conn*
  "A dynamic var to hold the Datomic connection."
  nil)

(def ^:dynamic *git*
  "A dynamic var to hold the GitPorcelain API."
  nil)

(def ^:dynamic *repo*
  "A dynamic var to hold the Git repository."
  nil)

(def ^:dynamic *repo-dir*
  "A dynamic var to hold a File object to the repository directory."
  nil)


(defn datomic-fixture
  [f]
  (let [uri (str "datomic:mem://" (d/squuid))]
    (d/create-database uri)
    (let [c (d/connect uri)]
      @(d/transact c schema)
      (binding [*conn* c]
        (f)))
    (d/delete-database uri)))

(defn git-fixture
  [f]
  (let [d (create-temp-dir)
        [g r] (init-repo d)]
    (binding [*git* g
              *repo* r
              *repo-dir* d]
      (f))))

(use-fixtures :each (compose-fixtures datomic-fixture git-fixture))


(defmacro is-only
  [x coll]
  `(do
     (is (= 1 (count ~coll)))
     (is (= ~x (first ~coll)))))

(defmacro is-coll
  [coll-expected coll-found]
  `(let [c# ~coll-found]
     (do
      (is (= (count ~coll-expected)
             (count c#)))
      (are [e#]
           (some #(= e# %) c#)
           ~@coll-expected))))


(deftest test-extract-and-import-commit
  (testing "creating, extracting, and importing a commit:"
    (let [commit (do
                   (spit (io/file *repo-dir* "a.txt") "contents of a\n")
                   (add-file-to-index *git* "a.txt")
                   (git/extract-commit-info
                    (git-commit-all *git* "added a.txt\n")))]

      (testing "extract the map of commit information"
        (is (= "author@example.org"
               (:author commit)))
        (is (= "committer@example.org"
               (:committer commit)))
        (is (empty? (:parents commit)))
        (is (= "added a.txt\n"
               (:msg commit))))

      (testing "importing the commit"
        (let [repoid (transact-repo-uri *conn* "test/.git")
              {db :db-after}
              @(d/transact *conn*
                           (commit-tx-data (d/db *conn*) *repo* repoid "test" commit))
              root-tree-node
              (get-root-tree-node db (:sha commit))
              children
              (get-tree-node-children root-tree-node)]
          (is (= 1 (count children)))
          (let [node (first children)]
            (is (= "a.txt" (node-name node)))
            (is (= '("test/a.txt") (node-paths node)))))))))


(deftest test-file-append
  (testing "a linear history of two commits, where a file is updated:"
    (let [f (io/file *repo-dir* "a.txt")
          c1 (do
               (spit f "line 1\n")
               (add-file-to-index *git* "a.txt")
               (git/extract-commit-info
                (git-commit-all *git* "added a.txt")))
          c2 (do
               (spit f "line 2\n" :append true)
               (git/extract-commit-info
                (git-commit-all *git* "updated a.txt")))
          repoid (transact-repo-uri *conn* "test/.git")
          _ (doseq [c [c1 c2]]
              @(d/transact *conn*
                           (commit-tx-data (d/db *conn*) *repo* repoid "test" c)))
          db (d/db *conn*)]

      (testing "examining first commit"
        (is-only "a.txt"
                 (find-commit-filenames db (:sha c1)))
        (is-only "test/a.txt"
                 (find-commit-filepaths db (:sha c1)))
        ;;hardcoded sha corresponds to "line 1\n"
        (is-only (:sha c1)
                 (find-blob-commits db "89b24ecec50c07aef0d6640a2a9f6dc354a33125")))

      (testing "examining second commit"
        (is-only "a.txt"
                 (find-commit-filenames db (:sha c2)))
        (is-only "test/a.txt"
                 (find-commit-filepaths db (:sha c2)))
        ;;hardcoded sha corresponds to "line 1\nline 2\n"
        (is-only (:sha c2)
                 (find-blob-commits db "7bba8c8e64b598d317cdf1bb8a63278f9fc241b1")))

      (testing "finding commits by file name and path"
        (is-coll [(:sha c1) (:sha c2)]
                 (find-filename-commits db "a.txt"))
        (is-coll [(:sha c1) (:sha c2)]
                 (find-filepath-commits db "test/a.txt"))))))


(deftest test-rename-file
  (testing "a linear history of two commits, where a file is renamed:"
    (let [f1 (io/file *repo-dir* "a.txt")
          c1 (do
               (spit f1 "line 1\n")
               (add-file-to-index *git* "a.txt")
               (git/extract-commit-info
                (git-commit-all *git* "added a.txt")))
          f2 (io/file *repo-dir* "b.txt")
          c2 (do
               (io/copy f1 f2)
               (io/delete-file f1)
               (add-file-to-index *git* "b.txt")
               (git/extract-commit-info
                (git-commit-all *git* "renamed a.txt to b.txt")))
          repoid (transact-repo-uri *conn* "test/.git")
          _ (doseq [c [c1 c2]]
              @(d/transact *conn*
                           (commit-tx-data (d/db *conn*) *repo* repoid "test" c)))
          db (d/db *conn*)]

      (testing "examining first commit"
        (is-only "a.txt"
                 (find-commit-filenames db (:sha c1))))

      (testing "examining second commit"
        (is-only "b.txt"
                 (find-commit-filenames db (:sha c2))))

      (testing "finding commits by file contents"
        (is-coll [(:sha c1) (:sha c2)]
                 (find-blob-commits db "89b24ecec50c07aef0d6640a2a9f6dc354a33125"))))))


(deftest test-merge-commit
  (testing "a branch and recursive merge in one file:"
    (let [f (io/file *repo-dir* "a.txt")
          c1 (do
               (spit f "line 1\n")
               (add-file-to-index *git* "a.txt")
               (git/extract-commit-info
                (git-commit-all *git* "added a.txt\n")))
          c2 (do
               (checkout-branch *git* "branch" true)
               (spit f "line 0\nline 1\n")
               (git/extract-commit-info
                (git-commit-all *git* "prepended a line to a.txt\n")))
          c3 (do
               (checkout-branch *git* "master")
               (spit f "line 1\nline 2\n")
               (git/extract-commit-info
                (git-commit-all *git* "appended a line to a.txt\n")))
          c4 (do
               (merge-branch-no-commit *git* "branch")
               (git/extract-commit-info
                (git-commit-all *git* (str "Merged branch 'branch'\n"))))
          repoid (transact-repo-uri *conn* "test/.git")
          _ (doseq [c [c1 c2 c3 c4]]
              @(d/transact *conn*
                           (commit-tx-data (d/db *conn*) *repo* repoid "test" c)))
          db (d/db *conn*)]

      (testing "examining initial commit on master"
        (is-only (:sha c1)
                 (find-blob-commits db "89b24ecec50c07aef0d6640a2a9f6dc354a33125")))

      (testing "examining second commit on branch"
        (is-only (:sha c1)
                 (:parents c2))
        (is-only (:sha c2)
                 (find-blob-commits db "2bbfc232c2e71c62004c15806843df3ffc3688d0")))

      (testing "examining third commit on master"
        (is-only (:sha c1)
                 (:parents c3))
        (is-only (:sha c3)
                 (find-blob-commits db "7bba8c8e64b598d317cdf1bb8a63278f9fc241b1")))

      (testing "examining merge commit"
        (is-coll [(:sha c2) (:sha c3)]
                 (:parents c4))
        (is-only (:sha c4)
                 (find-blob-commits db "73fc08f0c8b6a87eaad8f5991df3a150b501462d")))

      (testing "finding commits by file name and path"
        (is-coll [(:sha c1) (:sha c2) (:sha c3) (:sha c4)]
                 (find-filename-commits db "a.txt"))
        (is-coll [(:sha c1) (:sha c2) (:sha c3) (:sha c4)]
                 (find-filepath-commits db "test/a.txt"))))))


(deftest test-mix-of-file-ops
  (testing "a linear history of five commits, involving add, update, delete, revert:"
    (let [f (io/file *repo-dir* "a.txt")
          ;;start with initial commit of two files a.txt and b.txt
          c1 (do
               (spit f "line 1\n")
               (add-file-to-index *git* "a.txt")
               (spit (io/file *repo-dir* "b.txt")
                     "contents of b.txt\n")
               (add-file-to-index *git* "b.txt")
               (git/extract-commit-info
                (git-commit-all *git* "added a.txt and b.txt\n")))
          ;;then update a.txt by appending a line, and adding a new
          ;;file c.txt
          c2 (do
               (spit f "line 2\n" :append true)
               (spit (io/file *repo-dir* "c.txt")
                     "contents of c.txt\n")
               (add-file-to-index *git* "c.txt")
               (git/extract-commit-info
                (git-commit-all *git* "updated a.txt and added c.txt\n")))
          ;;then add a new file d.txt
          g (io/file *repo-dir* "d.txt")
          c3 (do
               (spit g "contents of d.txt\n")
               (add-file-to-index *git* "d.txt")
               (git/extract-commit-info
                (git-commit-all *git* "added d.txt\n")))
          ;;then only to delete that file -- this is equivalent to
          ;;reverting commit c3
          c4 (do
               (io/delete-file g)
               (git/extract-commit-info
                (git-commit-all *git* "deleted d.txt\n")))
          ;;finally, revert file a.txt to how it was after the
          ;;first commit
          c5 (do
               (spit f "line 1\n")
               (git/extract-commit-info
                (git-commit-all *git* "revert a.txt\n")))
          repoid (transact-repo-uri *conn* "test/.git")
          _ (doseq [c [c1 c2 c3 c4 c5]]
              @(d/transact *conn*
                           (commit-tx-data (d/db *conn*) *repo* repoid "test" c)))
          db (d/db *conn*)]

      (testing "examining first commit"
        (is-coll ["a.txt" "b.txt"]
                 (find-commit-filenames db (:sha c1))))

      (testing "finding commits with blobs containing 'line 1\\n'"
        ;;TODO might have expected c5 here?
        (is-only (:sha c1)
                 (find-blob-commits db "89b24ecec50c07aef0d6640a2a9f6dc354a33125")))

      (testing "examining second commit"
        (is-coll ["a.txt" "c.txt"]
                 (find-commit-filenames db (:sha c2))))

      (testing "finding commits with blobs containing 'line 1\\nline 2\\n'"
        ;;TODO this is because c4 has the same root tree as c2 as
        ;;it reverted c3
        (is-coll [(:sha c2) (:sha c4)]
                 (find-blob-commits db "7bba8c8e64b598d317cdf1bb8a63278f9fc241b1")))

      (testing "examining third commit"
        (is-only "d.txt"
                 (find-commit-filenames db (:sha c3))))

      (testing "examining fourth commit"
        ;;TODO as said above, c2 and c4 share a root tree
        (is-coll ["a.txt" "c.txt"]
                 (find-commit-filenames db (:sha c4))))

      (testing "examining fifth commit"
        ;;TODO maybe a tad surprising?
        (is (empty? (find-commit-filenames db (:sha c5)))))

      (testing "find commits"
        ;;TODO might have expected c5 here?
        (is-coll [(:sha c1) (:sha c2) (:sha c4)]
                 (find-filename-commits db "a.txt"))))))


(deftest test-path-trickery
  (testing "a linear history of four commits, to examine file path behavior:"
    (let [;;start with an initial commit of file a.txt in
          ;;subdirectory d1
          dir1 (doto (io/file *repo-dir* "d1") (.mkdir))
          f1 (io/file dir1 "a.txt")
          c1 (do
               (spit f1 "line 1\n")
               (add-file-to-index *git* "d1/a.txt")
               (git/extract-commit-info
                (git-commit-all *git* "added d1/a.txt\n")))
          ;;then add a file with the same name but distinct
          ;;contents into subdirectory d2
          dir2 (doto (io/file *repo-dir* "d2") (.mkdir))
          f2 (io/file dir2 "a.txt")
          c2 (do
               (spit f2 "line 2\n")
               (add-file-to-index *git* "d2/a.txt")
               (git/extract-commit-info
                (git-commit-all *git* "added d2/a.txt\n")))
          ;;then add a file with the same name and contents as the
          ;;previous commit but in subdirectory d3
          dir3 (doto (io/file *repo-dir* "d3") (.mkdir))
          f3 (io/file dir3 "a.txt")
          c3 (do
               (spit f3 "line 2\n")
               (add-file-to-index *git* "d3/a.txt")
               (git/extract-commit-info
                (git-commit-all *git* "added d3/a.txt\n")))
          ;;the repository is now is a state where there are three
          ;;files, all of which have the same name, two of which
          ;;have the same content, and all are in distinct
          ;;subdirectories
          ;;finally overwrite the contents of d2/a.txt with the
          ;;contents of d1/a.txt
          c4 (do
               (spit f2 "line 1\n")
               (git/extract-commit-info
                (git-commit-all *git* "copied d1/a.txt to d2/a.txt\n")))
          ;;before, two files contained 'line 2\\n'
          ;;now, two files contain 'line 1\\n'
          repoid (transact-repo-uri *conn* "test/.git")
          _ (doseq [c [c1 c2 c3 c4]]
              @(d/transact *conn*
                           (commit-tx-data (d/db *conn*) *repo* repoid "test" c)))
          db (d/db *conn*)]

      (testing "find commits with file a.txt"
        (is-coll [(:sha c1) (:sha c2) (:sha c3) (:sha c4)]
                 (find-filename-commits db "a.txt")))

      (testing "find commits with path d1/a.txt"
        ;;TODO might have expected just c1?
        (is-coll [(:sha c1) (:sha c4)]
                 (find-filepath-commits db "test/d1/a.txt")))

      (testing "find commits with path d2/a.txt"
        ;;TODO might have expected just c2 and c4?
        (is-coll [(:sha c1) (:sha c2) (:sha c3) (:sha c4)]
                 (find-filepath-commits db "test/d2/a.txt")))

      (testing "find commits with path d3/a.txt"
        ;;TODO might have expected just c3?
        (is-coll [(:sha c2) (:sha c3)]
                 (find-filepath-commits db "test/d3/a.txt")))

      (testing "find commits for blob with contents 'line 1\\n'"
        (is-coll [(:sha c1) (:sha c4)]
                 (find-blob-commits db "89b24ecec50c07aef0d6640a2a9f6dc354a33125")))

      (testing "find commits for blob with contents 'line 2\\n'"
        (is-coll [(:sha c2) (:sha c3)]
                 (find-blob-commits db "b7e242c00cdad96cf88a626557eba4deab43b52f"))))))
