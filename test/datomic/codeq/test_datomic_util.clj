(ns datomic.codeq.test-datomic-util
  (:use [datomic.codeq.util :only [index-get-id]]
        [datomic.codeq.core :only [schema]])
  (:require [datomic.api :as d]))


;; Transact Helpers

(defn transact-repo-uri
  "Transact the given repository uri.
   Returns the entity id of the transacted fact."
  [conn repo-uri]
  (let [id (d/tempid :db.part/user)
        {:keys [db-after tempids]}
        @(d/transact conn [[:db/add id :repo/uri repo-uri]])
        repoid (d/resolve-tempid db-after tempids id)]
    repoid))


;; Query Helpers

(def rules
  '[;;find all blobs ?b that are decendants of tree node ?n
    [(node-blobs ?n ?b)
     [?n :node/object ?b] [?b :git/type :blob]]
    [(node-blobs ?n ?b)
     [?n :node/object ?t] [?t :git/type :tree]
     [?t :tree/nodes ?n2] (node-files ?n2 ?b)]

    ;;find all file names ?name for blobs that are decendants of tree
    ;;node ?n
    [(node-files ?n ?name)
     [?n :node/object ?b] [?b :git/type :blob]
     [?n :node/filename ?f] [?f :file/name ?name]]
    [(node-files ?n ?name)
     [?n :node/object ?t] [?t :git/type :tree]
     [?t :tree/nodes ?n2] (node-files ?n2 ?name)]

    ;;find all file paths ?path for blobs that are decendants of tree
    ;;node ?n
    [(node-paths ?n ?path)
     [?n :node/object ?b] [?b :git/type :blob]
     [?n :node/paths ?p] [?p :file/name ?path]]
    [(node-paths ?n ?path)
     [?n :node/object ?t] [?t :git/type :tree]
     [?t :tree/nodes ?n2] (node-paths ?n2 ?path)]

    ;;find all tree nodes ?n that reference this object (blob/tree) or
    ;;are ancestors of tree nodes that do
    [(object-nodes ?o ?n)
     [?n :node/object ?o]]
    [(object-nodes ?o ?n)
     [?n2 :node/object ?o]
     [?t :tree/nodes ?n2] (object-nodes ?t ?n)]

    ;;find all tree nodes ?n that have file name ?name or are
    ;;ancestors of tree nodes that do
    [(file-nodes ?name ?n)
     [?f :file/name ?name] [?n :node/filename ?f]]
    [(file-nodes ?name ?n)
     (file-nodes ?name ?n2)
     [?t :tree/nodes ?n2] [?n :node/object ?t]]

    ;;find all tree nodes ?n that have file path ?path or are
    ;;ancestors of tree nodes that do
    [(path-nodes ?path ?n)
     [?p :file/name ?path] [?n :node/paths ?p]]
    [(path-nodes ?path ?n)
     (path-nodes ?path ?n2)
     [?t :tree/nodes ?n2] [?n :node/object ?t]]

    ;;find all blobs ?b that are within commit ?c's tree
    [(commit-blobs ?c ?b)
     [?c :commit/tree ?root] (node-blobs ?root ?b)]

    ;;find all file names ?name that are within commit ?c's tree
    [(commit-files ?c ?name)
     [?c :commit/tree ?root] (node-files ?root ?name)]

    ;;find all file paths ?path that are within commit ?c's tree
    [(commit-paths ?c ?path)
     [?c :commit/tree ?root] (node-paths ?root ?path)]

    ;;find all codeqs ?cq for blobs that are within commit ?c's tree
    [(commit-codeqs ?c ?cq)
     (commit-blobs ?c ?b) [?cq :codeq/file ?b]]

    ;;find all commits ?c that include blob ?b
    [(blob-commits ?b ?c)
     (object-nodes ?b ?n) [?c :commit/tree ?n]]

    ;;find all commits ?c that include a file named ?name
    [(file-commits ?name ?c)
     (file-nodes ?name ?n) [?c :commit/tree ?n]]

    ;;find all commits ?c that include a file path named ?path
    [(path-commits ?path ?c)
     (path-nodes ?path ?n) [?c :commit/tree ?n]]

    ;;find all commits ?c that include a codeq ?cq
    [(codeq-commits ?cq ?c)
     [?cq :codeq/file ?b] (blob-commits ?b ?c)]])


(defn find-commit-filenames
  "Returns all file names that are part of the given commit."
  [db commit-sha]
  (mapv first
        (d/q '[:find ?name
               :in $ % ?sha
               :where
               [?c :git/sha ?sha]
               (commit-files ?c ?name)]
             db rules commit-sha)))

(defn find-commit-filepaths
  "Returns all file paths that are part of the given commit."
  [db commit-sha]
  (mapv first
        (d/q '[:find ?path
               :in $ % ?sha
               :where
               [?c :git/sha ?sha]
               (commit-paths ?c ?path)]
             db rules commit-sha)))

(defn find-filename-commits
  "Returns all commits that reference the given file name."
  [db filename]
  (mapv first
        (d/q '[:find ?sha
               :in $ % ?name
               :where
               (file-commits ?name ?c)
               [?c :git/sha ?sha]]
             db rules filename)))

(defn find-filepath-commits
  "Returns all commits that reference the given file path."
  [db path]
  (mapv first
        (d/q '[:find ?sha
               :in $ % ?path
               :where
               (path-commits ?path ?c)
               [?c :git/sha ?sha]]
             db rules path)))


(defn find-blob-commits
  "Returns all commits that reference the given blob sha."
  [db blob-sha]
  (let [blob-id (index-get-id db :git/sha blob-sha)]
    (mapv first
          (d/q '[:find ?sha
                 :in $ % ?blob-id
                 :where
                 (blob-commits ?blob-id ?c)
                 [?c :git/sha ?sha]]
               db rules blob-id))))


;; Entity Helpers

(defn get-root-tree-node
  "Returns the root tree node entity for the given commit sha."
  [db commit-sha]
  (->> (d/datoms db :avet :git/sha commit-sha)
       first :e (d/entity db) :commit/tree))

(defn node-name
  "Returns the file name of a tree node entity."
  [tree-node-entity]
  (get-in tree-node-entity [:node/filename :file/name]))

(defn node-paths
  "Returns the file paths of a tree node entity."
  [tree-node-entity]
  (->> tree-node-entity
       :node/paths
       (map :file/name)))

(defn get-tree-node-children
  "Returns the children tree nodes of a tree node."
  [tree-node-entity]
  (let [o (:node/object tree-node-entity)]
    (assert (= :tree (:git/type o)))
    (:tree/nodes o)))
