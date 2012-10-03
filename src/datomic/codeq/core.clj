;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns datomic.codeq.core
  (:require [datomic.api :as d]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import java.util.Date)
  (:gen-class))

(set! *warn-on-reflection* true)

(def schema
     [
      {:db/id #db/id[:db.part/db]
       :db/ident :git/commit
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Associate tx with this git commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :codeq/op
       :db/valueType :db.type/keyword
       :db/index true
       :db/cardinality :db.cardinality/one
       :db/doc "Associate tx with this operation - one of :import, :analyze"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/type
       :db/valueType :db.type/keyword
       :db/cardinality :db.cardinality/one
       :db/index true
       :db/doc "Type enum for git objects - one of :commit, :tree, :blob, :tag"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/sha
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "A git sha, should be in repo"
       :db/unique :db.unique/identity
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/parents
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/many
       :db/doc "Parents of a commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/tree
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Root node of a commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/message
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "A commit message"
       :db/fulltext true
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/author
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Person who authored a commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/authoredAt
       :db/valueType :db.type/instant
       :db/cardinality :db.cardinality/one
       :db/doc "Timestamp of authorship of commit"
       :db/index true
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/committer
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Person who committed a commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/committedAt
       :db/valueType :db.type/instant
       :db/cardinality :db.cardinality/one
       :db/doc "Timestamp of commit"
       :db/index true
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/nodes
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/many
       :db/doc "Nodes of a git tree"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/path
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "Path of a tree node"
       :db/index true
       :db/fulltext true
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/object
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Git object (tree/blob) in a tree node"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :git/prior
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Node containing prior value of a git object"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :email/address
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "An email address"
       :db/unique :db.unique/identity
       :db.install/_attribute :db.part/db}
      ])

(defn ^java.io.Reader exec-stream 
  [^String cmd]
  (-> (Runtime/getRuntime)
      (.exec cmd) 
      .getInputStream 
      io/reader))

(defn ensure-schema [conn]
  (or (-> conn d/db (d/entid :git/commit))
      @(d/transact conn schema)))

;;example commit - git cat-file -p
;;tree d81cd432f2050c84a3d742caa35ccb8298d51e9d
;;author Rich Hickey <richhickey@gmail.com> 1348842448 -0400
;;committer Rich Hickey <richhickey@gmail.com> 1348842448 -0400

;; or

;;tree ba63180c1d120b469b275aef5da479ab6c3e2afd
;;parent c3bd979cfe65da35253b25cb62aad4271430405c
;;maybe more parents
;;author Rich Hickey <richhickey@gmail.com> 1348869325 -0400
;;committer Rich Hickey <richhickey@gmail.com> 1348869325 -0400


;;example tree
;;100644 blob ee508f768d92ba23e66c4badedd46aa216963ee1	.gitignore
;;100644 blob b60ea231eb47eb98395237df17550dee9b38fb72	README.md
;;040000 tree bcfca612efa4ff65b3eb07f6889ebf73afb0e288	doc
;;100644 blob 813c07d8cd27226ddd146ddd1d27fdbde10071eb	epl-v10.html
;;100644 blob f8b5a769bcc74ee35b9a8becbbe49d4904ab8abe	project.clj
;;040000 tree 6b880666740300ac57361d5aee1a90488ba1305c	src
;;040000 tree 407924e4812c72c880b011b5a1e0b9cb4eb68cfa	test

(defn dir
  "Returns [[sha :type path] ...]"
  [tree]
  (with-open [s (exec-stream (str "git cat-file -p " tree))]
    (let [es (line-seq s)]
      (mapv #(let [ss (string/split ^String % #"\s")]
               [(nth ss 2)
                (keyword (nth ss 1))
                (subs % (inc (.indexOf ^String % "\t")) (count %))])
            es))))

(defn commit
  [[sha msg]]
  (let [trim-email (fn [s] (subs s 1 (dec (count s))))
        dt (fn [ds] (Date. (* 1000 (Integer/parseInt ds))))
        [tree parents author committer]
        (with-open [s (exec-stream (str "git cat-file -p " sha))]
          (let [lines (mapv #(string/split % #"\s") (line-seq s))
                tree (-> lines (nth 0) (nth 1))
                [plines xs] (split-with #(= (nth % 0) "parent") (rest lines))]
            [tree
             (seq (map second plines))
             (vec (reverse (first xs)))
             (vec (reverse (second xs)))]))]
    {:sha sha
     :msg msg
     :tree tree
     :parents parents
     :author (trim-email (author 2))
     :authored (dt (author 1))
     :committer (trim-email (committer 2))
     :committed (dt (committer 1))}))

(defn index-get-id
  [db attr v]
  (let [d (first (d/index-range db attr v nil))]
    (when (and d (= (:v d) v))
      (:e d))))

(defn index->id-fn
  [db attr]
  (memoize
   (fn [x]
     (or (index-get-id db attr x)
         (d/tempid :db.part/user)))))

(defmacro cond->
  [init & steps]
  (assert (even? (count steps)))
  (let [g (gensym)
        pstep (fn [[pred step]] `(if ~pred (-> ~g ~step) ~g))]
    `(let [~g ~init
           ~@(interleave (repeat g) (map pstep (partition 2 steps)))]
       ~g)))

(defn commit-tx-data
  [db {:keys [sha msg tree parents author authored committer committed] :as commit}]
  (let [tempid? map? ;;todo - better pred
        sha->id (index->id-fn db :git/sha)
        email->id (index->id-fn db :email/address)
        authorid (email->id author)
        committerid (email->id committer)
        cid (d/tempid :db.part/user)
        tx-data (fn f [[sha type path]]
                  (let [id (sha->id sha)
                        nodeid (or (and (not (tempid? id))
                                        (ffirst (d/q '[:find ?e :in $ ?path ?id
                                                       :where [?e :git/path ?path] [?e :git/object ?id]]
                                                     db path id)))
                                   (d/tempid :db.part/user))
                        data (cond-> []
                                     (tempid? nodeid) (conj {:db/id nodeid :git/path path :git/object id})
                                     (tempid? id) (conj {:db/id id :git/sha sha :git/type type}))
                        data (if (and (tempid? id) (= type :tree))
                               (let [es (dir sha)]
                                 (reduce (fn [data child]
                                           (let [[cid cdata] (f child)
                                                 data (into data cdata)]
                                             (conj data [:db/add id :git/nodes cid])))
                                         data es))
                               data)]
                    [nodeid data]))
        [treeid treedata] (tx-data [tree :tree ""])
        tx (into treedata
                 [{:db/id (d/tempid :db.part/tx)
                   :git/commit cid
                   :codeq/op :import}
                  (cond-> {:db/id cid
                           :git/type :commit
                           :git/tree treeid
                           :git/sha sha
                           :git/author authorid
                           :git/authoredAt authored
                           :git/committer committerid
                           :git/committedAt committed
                           }
                          msg (assoc :git/message msg)
                          parents (assoc :git/parents
                                    (mapv (fn [p]
                                            (let [id (sha->id p)]
                                              (assert (not (tempid? id))
                                                      (str "Parent " p " not previously imported"))
                                              id))
                                          parents)))])
        tx (cond-> tx
                   (tempid? authorid) (conj [:db/add authorid :email/address author])
                   (and (not= committer author) (tempid? committerid)) (conj [:db/add committerid :email/address committer]))]
    tx))

(defn commits
  "Returns log as [[sha msg] ...], in commit order. commit-name may be nil
  or any acceptable commit name arg for git log"
  [commit-name]
  (let [commits (with-open [s (exec-stream (str "git log --pretty=oneline " commit-name))]
                  (reverse
                   (mapv
                    #(vector (subs % 0 40)
                             (subs % 41 (count %)))
                    (line-seq s))))] 
    commits))

(defn unimported-commits
  [db commit-name]
  (let [imported (into {}
                       (d/q '[:find ?sha ?e
                              :where
                              [?tx :codeq/op :import]
                              [?tx :git/commit ?e]
                              [?e :git/sha ?sha]]
                            db))]
    (pmap commit (remove (fn [[sha _]] (imported sha)) (commits commit-name)))))


(defn ensure-db [db-uri]
  (let [newdb? (d/create-database db-uri)
        conn (d/connect db-uri)]
    (ensure-schema conn)
    conn))

(defn import-git
  [conn commits]
  (doseq [commit commits]
    (let [db (d/db conn)]
      (prn (:sha commit))
      (d/transact conn (commit-tx-data db commit)))))

(defn -main
  [& [db-uri commit]]
  (if db-uri
    (let [conn (ensure-db db-uri)] 
      (import-git conn (unimported-commits (d/db conn) commit)))
    (println "Usage: datomic.codeq.core db-uri [commit-name]")))


(comment
;;(def uri "datomic:mem://codeq")
(def uri "datomic:free://localhost:4334/codeq")
(datomic.codeq.core/-main uri "c3bd979cfe65da35253b25cb62aad4271430405c")
(datomic.codeq.core/-main uri  "20f8db11804afc8c5a1752257d5fdfcc2d131d08")
(datomic.codeq.core/-main uri)
(require '[datomic.api :as d])
(def conn (d/connect uri))
(def db (d/db conn))
(seq (d/datoms db :aevt :git/type))
)