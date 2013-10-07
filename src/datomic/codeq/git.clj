
(ns datomic.codeq.git
  (:import [org.eclipse.jgit.lib FileMode ObjectLoader Ref Repository RepositoryBuilder]
           [org.eclipse.jgit.revwalk RevCommit RevSort RevTree RevWalk]
           org.eclipse.jgit.revwalk.filter.RevFilter
           org.eclipse.jgit.treewalk.TreeWalk
           org.eclipse.jgit.storage.file.FileRepositoryBuilder))


(defn ^Repository open-existing-repo
  "Open an exisiting git repository by
   scanning GIT_* environment variables
   and scanning up the file system tree."
  []
  (.. (FileRepositoryBuilder.)
      (readEnvironment)
      (findGitDir)
      (build)))


(defn get-origin-uri
  "Lookup the uri for the 'origin' remote, and
   extract the repository name. Returns [uri name]."
  [^Repository repo]
  (if-let [^String uri (.. repo (getConfig) (getString "remote" "origin" "url"))]
    (let [noff (.lastIndexOf uri "/")
          noff (if (not (pos? noff)) (.lastIndexOf uri ":") noff)
          name (subs uri (inc noff))
          _ (assert (pos? (count name)) "Can't find remote origin")
          name (if (.endsWith name ".git") (subs name 0 (.indexOf name ".")) name)]
      [uri name])
    (throw (ex-info "No remote 'origin' configured for this repository."
                    {:remotes (.. repo (getConfig) (getSubsections "remote"))}))))


(defn walk-all-commits
  "Walk all commits in reverse topological order.

   repo - the repository to walk

   imported-commits - a (possibily empty) map of {sha commitid ...}

   rev-str - an optional git revision string

   Returns a lazy sequence of RevCommit objects. The walk will
   efficiently skip over SHAs that are keys in the imported-commits map."
  [^Repository repo
   imported-commits
   rev-str]
  (let [rev-walk (RevWalk. repo)
        commit-id
        (if rev-str
          (or (.resolve repo ^String rev-str)
              (throw (ex-info (str "Can't resolve git revision string." rev-str)
                              {:revision-string rev-str})))
          (.. repo (getRef "refs/heads/master") (getObjectId)))
        rev-commit (.parseCommit rev-walk commit-id)
        rev-filter
        (proxy [RevFilter] []
          (clone [] this)
          (include [rev-walk rev-commit]
            (nil? (imported-commits (.getName ^RevCommit rev-commit))))
          (requiresCommitBody [] false))]
    (seq (doto rev-walk
           (.markStart rev-commit)
           (.setRevFilter rev-filter)
           (.sort RevSort/TOPO true)
           (.sort RevSort/REVERSE true)))))


(defn extract-commit-info
  "Returns a map of information extracted from a RevCommit object."
  [^RevCommit commit]
  (let [author (.getAuthorIdent commit)
        committer (.getCommitterIdent commit)]
    {:sha (.getName commit)
     :msg (.getFullMessage commit)
     :tree (.. commit (getTree) (getName))
     :parents (->> commit
                   (.getParents)
                   (map #(.getName ^RevCommit %)))
     :author (.getEmailAddress author)
     :authored (.getWhen author)
     :committer (.getEmailAddress committer)
     :committed (.getWhen committer)}))


(defn lookup-rev-tree
  "Return the RevTree object that resolves from a tree SHA id"
  [^Repository repo
   ^String sha]
  (->> sha
       (.resolve repo)
       (.parseTree (RevWalk. repo))))


(defn shallow-tree-walk
  "Walks one level of a RevTree object,
  returning the trees and blobs contained therein.

  Returns [[sha (:tree OR :blob) file-name] ...]"
  [^Repository repo
   ^RevTree tree]
  (let [tree-walk (doto (TreeWalk. repo)
                    (.addTree tree)
                    (.setRecursive false))
        dir (transient [])]
    (while (.next tree-walk)
      (conj! dir
             [(.. tree-walk (getObjectId 0) (getName))
              (if (= (.getFileMode tree-walk 0)
                     FileMode/TREE)
                :tree :blob)
              (.getNameString tree-walk)]))
    (persistent! dir)))

