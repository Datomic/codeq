
(ns datomic.codeq.git
  (:import [org.eclipse.jgit.lib FileMode ObjectLoader Ref Repository RepositoryBuilder]
           [org.eclipse.jgit.revwalk RevCommit RevSort RevTree RevWalk]
           org.eclipse.jgit.revwalk.filter.RevFilter
           org.eclipse.jgit.treewalk.TreeWalk
           org.eclipse.jgit.storage.file.FileRepositoryBuilder))


(set! *warn-on-reflection* true)


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
            (let [sha (.getName ^RevCommit rev-commit)
                  incl (nil? (imported-commits sha))]
              (when-not incl
                (println "Skipping commit: " sha))
              incl))
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


(defn deep-tree-walk
  "Walk over the entire tree of repository repo with name repo-name
   starting from the point identitfied by tree-sha, using the function
   tree-walker to produce transaction data.

   Returns the root tree node id along with the accumulation of
   transaction data produced by calling tree-walker on each node of
   the tree walk.

   The tree walker function is given a map
   {:sha :type :path :filename :parent}
   Where the :type is :tree or :blob. :parent is nil at the root of
   the tree walk. The tree walker function must return a map
   {:node-id :object-id :new-path :data}
   Which contains the transaction data along with the tree node id
   and object id for linking the node and object. Also the boolean
   :new-path indicates if the path that the walker has processed is
   new. If so, deep-tree-walk will step into subtrees."
  [^Repository repo
   repo-name
   ^String tree-sha
   tree-walker]
  (let [;;resolve tree-sha to a revision tree
        rev-tree (->> tree-sha
                      (.resolve repo)
                      (.parseTree (RevWalk. repo)))
        ;;set revision tree as starting point for tree walk
        tree-walk (doto (TreeWalk. repo) (.addTree rev-tree))
        ;;create a root tree node from the repository name
        {root-nodeid :node-id root-treeid :object-id new-root :new-path seed-data :data}
        (tree-walker {:sha tree-sha :type :tree
                      :path repo-name :filename repo-name
                      :parent nil})]
    (if-not new-root
      ;;if root node is not new, then there is nothing to walk
      [root-nodeid seed-data]
      (loop [stack (list root-treeid)
            depth (.getDepth tree-walk)
            tx-data seed-data]
       (if-not (.next tree-walk)
         [root-nodeid tx-data]
         (let [curr-id (.getObjectId tree-walk 0)
               sha (.getName curr-id)
               path (str repo-name "/" (.getPathString tree-walk))
               filename (.getNameString tree-walk)]
           (cond
            ;;tree walk is pointing at a tree to step into
            (.isSubtree tree-walk)
            (let [{:keys [object-id data new-path]}
                  (tree-walker {:sha sha :type :tree
                                :path path :filename filename
                                :parent (peek stack)})]
              (if new-path
                ;;enter subtree only if it's a new path
                (do (.enterSubtree tree-walk)
                    (recur (conj stack object-id) (.getDepth tree-walk)
                           (into tx-data data)))
                ;;else skip over it
                (recur stack depth (into tx-data data))))
            ;;depth has decrease so we must have popped out of a subtree
            (< (.getDepth tree-walk) depth)
            (let [new-depth (.getDepth tree-walk)
                  new-stack (seq (drop (- depth new-depth) stack))
                  {:keys [data]} (tree-walker {:sha sha :type :blob
                                               :path path :filename filename
                                               :parent (peek new-stack)})]
              (recur new-stack new-depth (into tx-data data)))
            ;;else continue at same depth with another blob
            :else
            (let [{:keys [data]}
                  (tree-walker {:sha sha :type :blob
                                :path path :filename filename
                                :parent (peek stack)})]
              (recur stack depth (into tx-data data))))))))))
