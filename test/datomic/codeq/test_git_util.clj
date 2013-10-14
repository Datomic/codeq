(ns datomic.codeq.test-git-util
  (:import java.io.File
           java.nio.file.Files
           java.nio.file.attribute.FileAttribute
           org.eclipse.jgit.api.Git
           org.eclipse.jgit.lib.PersonIdent))


(defn create-temp-dir
  "Returns a java.io.File handle to a fresh temporary directory."
  []
  (.toFile (Files/createTempDirectory "codeqtest" (into-array FileAttribute []))))


(defn init-repo
  "Returns a GitPorcelain API object and an initialized git
   repository for the given directory."
  [dir]
  (let [git (.. (Git/init) (setDirectory dir) (call))]
    [git (.getRepository git)]))


(defn create-person-ident
  "Returns a PersonIdent from the name and num
   with the current timestamp."
  [name num]
  (let [ident (str name num)
        email (str ident "@example.org")]
    (PersonIdent. ident email)))


(defn author-ident
  "Returns a PersonIdent called 'author'."
  [& [num]]
  (create-person-ident "author" num))


(defn committer-ident
  "Returns a Personident called 'committer'."
  [& [num]]
  (create-person-ident "committer" num))


(defn add-file-to-index
  "Add the given file name to the git index."
  [git name]
  (.. git (add) (addFilepattern name) (call)))


(defn git-commit-all
  "Perform a git commit.
   Equivalent to
     git commit -a -m msg"
  [git msg]
  (.. git (commit)
      (setAll true)
      (setAuthor (author-ident))
      (setCommitter (committer-ident))
      (setMessage msg)
      (call)))


(defn checkout-branch
  "Perform a git checkout.
     git checkout branch-name
   If is-new is true then the branch is created.
     git checkout -b branch-name"
  [git branch-name & [is-new]]
  (.. git
      (checkout)
      (setCreateBranch (true? is-new))
      (setName branch-name)
      (call)))


(defn merge-branch-no-commit
  "Perform a git merge, but stopping before committing.
   Equivalent to
     git merge --no-commit branch-name"
  [git branch-name]
  (let [repo (.getRepository git)
        branch-id (.resolve repo branch-name)]
    (.. git
        (merge)
        (include branch-id)
        (setCommit false)
        (call))))
