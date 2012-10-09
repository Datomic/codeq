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
            [clojure.set]
            [clojure.string :as string]
            [datomic.codeq.util :refer [cond-> index->id-fn tempid?]]
            [datomic.codeq.analyzer :as az]
            [datomic.codeq.analyzers.clj])
  (:import java.util.Date)
  (:gen-class))

(set! *warn-on-reflection* true)

(def schema
     [
      ;;tx attrs
      {:db/id #db/id[:db.part/db]
       :db/ident :tx/commit
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Associate tx with this git commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :tx/file
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Associate tx with this git blob"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :tx/analyzer
       :db/valueType :db.type/keyword
       :db/cardinality :db.cardinality/one
       :db/index true
       :db/doc "Associate tx with this analyzer"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :tx/analyzerRev
       :db/valueType :db.type/long
       :db/cardinality :db.cardinality/one
       :db/doc "Associate tx with this analyzer revision"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :tx/op
       :db/valueType :db.type/keyword
       :db/index true
       :db/cardinality :db.cardinality/one
       :db/doc "Associate tx with this operation - one of :import, :analyze"
       :db.install/_attribute :db.part/db}

      ;;git stuff
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
       :db/ident :repo/commits
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/many
       :db/doc "Associate repo with these git commits"
       :db.install/_attribute :db.part/db}
      
      {:db/id #db/id[:db.part/db]
       :db/ident :repo/uri
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "A git repo uri"
       :db/unique :db.unique/identity
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :commit/parents
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/many
       :db/doc "Parents of a commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :commit/tree
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Root node of a commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :commit/message
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "A commit message"
       :db/fulltext true
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :commit/author
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Person who authored a commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :commit/authoredAt
       :db/valueType :db.type/instant
       :db/cardinality :db.cardinality/one
       :db/doc "Timestamp of authorship of commit"
       :db/index true
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :commit/committer
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Person who committed a commit"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :commit/committedAt
       :db/valueType :db.type/instant
       :db/cardinality :db.cardinality/one
       :db/doc "Timestamp of commit"
       :db/index true
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :tree/nodes
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/many
       :db/doc "Nodes of a git tree"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :node/filename
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "filename of a tree node"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :node/paths
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/many
       :db/doc "paths of a tree node"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :node/object
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

      {:db/id #db/id[:db.part/db]
       :db/ident :file/name
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "A filename"
       :db/fulltext true
       :db/unique :db.unique/identity
       :db.install/_attribute :db.part/db}

      ;;codeq stuff
      {:db/id #db/id[:db.part/db]
       :db/ident :codeq/file
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Git file containing codeq"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :codeq/loc
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "Location of codeq in file. A location string in format \"line col endline endcol\", one-based"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :codeq/parent
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Parent (containing) codeq of codeq (if one)"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :codeq/code
       :db/valueType :db.type/ref
       :db/cardinality :db.cardinality/one
       :db/doc "Code entity of codeq"
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :code/sha
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "SHA of whitespace-minified code segment text: consecutive ws becomes a single space, then trim. ws-sensitive langs don't minify."
       :db/unique :db.unique/identity
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :code/text
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "The source code for a code segment"
       ;;:db/fulltext true
       :db.install/_attribute :db.part/db}

      {:db/id #db/id[:db.part/db]
       :db/ident :code/name
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/doc "A globally-namespaced programming language identifier"
       :db/fulltext true
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
  (or (-> conn d/db (d/entid :tx/commit))
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
;;then blank line
;;then commit message


;;example tree
;;100644 blob ee508f768d92ba23e66c4badedd46aa216963ee1	.gitignore
;;100644 blob b60ea231eb47eb98395237df17550dee9b38fb72	README.md
;;040000 tree bcfca612efa4ff65b3eb07f6889ebf73afb0e288	doc
;;100644 blob 813c07d8cd27226ddd146ddd1d27fdbde10071eb	epl-v10.html
;;100644 blob f8b5a769bcc74ee35b9a8becbbe49d4904ab8abe	project.clj
;;040000 tree 6b880666740300ac57361d5aee1a90488ba1305c	src
;;040000 tree 407924e4812c72c880b011b5a1e0b9cb4eb68cfa	test

;; example git remote origin
;;RichMacPro:codeq rich$ git remote show -n origin
;;* remote origin
;;  Fetch URL: https://github.com/Datomic/codeq.git
;;  Push  URL: https://github.com/Datomic/codeq.git
;;  HEAD branch: (not queried)

(defn get-repo-uri
  "returns [uri name]"
  []
  (with-open [s (exec-stream (str "git remote show -n origin"))]
    (let [es (line-seq s)
          ^String line (second es)
          uri (subs line (inc (.lastIndexOf line " ")))
          noff (.lastIndexOf uri "/")
          noff (if (not (pos? noff)) (.lastIndexOf uri ":") noff)
          name (subs uri (inc noff))
          _ (assert (and (pos? (count name)) (.endsWith name ".git")) "Can't find remote origin")
          name (subs name 0 (.indexOf name "."))]
      [uri name])))

(defn dir
  "Returns [[sha :type filename] ...]"
  [tree]
  (with-open [s (exec-stream (str "git cat-file -p " tree))]
    (let [es (line-seq s)]
      (mapv #(let [ss (string/split ^String % #"\s")]
               [(nth ss 2)
                (keyword (nth ss 1))
                (subs % (inc (.indexOf ^String % "\t")) (count %))])
            es))))

(defn commit
  [[sha _]]
  (let [trim-email (fn [s] (subs s 1 (dec (count s))))
        dt (fn [ds] (Date. (* 1000 (Integer/parseInt ds))))
        [tree parents author committer msg]
        (with-open [s (exec-stream (str "git cat-file -p " sha))]
          (let [lines (line-seq s)
                slines (mapv #(string/split % #"\s") lines)
                tree (-> slines (nth 0) (nth 1))
                [plines xs] (split-with #(= (nth % 0) "parent") (rest slines))]
            [tree
             (seq (map second plines))
             (vec (reverse (first xs)))
             (vec (reverse (second xs)))
             (->> lines 
                  (drop-while #(not= % ""))
                  rest
                  (interpose "\n")
                  (apply str))]))]
    {:sha sha
     :msg msg
     :tree tree
     :parents parents
     :author (trim-email (author 2))
     :authored (dt (author 1))
     :committer (trim-email (committer 2))
     :committed (dt (committer 1))}))



(defn commit-tx-data
  [db repo repo-name {:keys [sha msg tree parents author authored committer committed] :as commit}]
  (let [tempid? map? ;;todo - better pred
        sha->id (index->id-fn db :git/sha)
        email->id (index->id-fn db :email/address)
        filename->id (index->id-fn db :file/name)
        authorid (email->id author)
        committerid (email->id committer)
        cid (d/tempid :db.part/user)
        tx-data (fn f [inpath [sha type filename]]
                  (let [path (str inpath filename)
                        id (sha->id sha)
                        filenameid (filename->id filename)
                        pathid (filename->id path)
                        nodeid (or (and (not (tempid? id))
                                        (not (tempid? filenameid))
                                        (ffirst (d/q '[:find ?e :in $ ?filename ?id
                                                       :where [?e :node/filename ?filename] [?e :node/object ?id]]
                                                     db filenameid id)))
                                   (d/tempid :db.part/user))
                        newpath (or (tempid? pathid) (tempid? nodeid)
                                    (not (ffirst (d/q '[:find ?node :in $ ?path
                                                        :where [?node :node/paths ?path]]
                                                      db pathid))))
                        data (cond-> []
                                     (tempid? filenameid) (conj [:db/add filenameid :file/name filename])
                                     (tempid? pathid) (conj [:db/add pathid :file/name path])
                                     (tempid? nodeid) (conj {:db/id nodeid :node/filename filenameid :node/object id})
                                     newpath (conj [:db/add nodeid :node/paths pathid])
                                     (tempid? id) (conj {:db/id id :git/sha sha :git/type type}))
                        data (if (and newpath (= type :tree))
                               (let [es (dir sha)]
                                 (reduce (fn [data child]
                                           (let [[cid cdata] (f (str path "/") child)
                                                 data (into data cdata)]
                                             (cond-> data
                                                     (tempid? id) (conj [:db/add id :tree/nodes cid]))))
                                         data es))
                               data)]
                    [nodeid data]))
        [treeid treedata] (tx-data nil [tree :tree repo-name])
        tx (into treedata
                 [[:db/add repo :repo/commits cid]
                  {:db/id (d/tempid :db.part/tx)
                   :tx/commit cid
                   :tx/op :import}
                  (cond-> {:db/id cid
                           :git/type :commit
                           :commit/tree treeid
                           :git/sha sha
                           :commit/author authorid
                           :commit/authoredAt authored
                           :commit/committer committerid
                           :commit/committedAt committed
                           }
                          msg (assoc :commit/message msg)
                          parents (assoc :commit/parents
                                    (mapv (fn [p]
                                            (let [id (sha->id p)]
                                              (assert (not (tempid? id))
                                                      (str "Parent " p " not previously imported"))
                                              id))
                                          parents)))])
        tx (cond-> tx
                   (tempid? authorid) 
                   (conj [:db/add authorid :email/address author])
                   
                   (and (not= committer author) (tempid? committerid)) 
                   (conj [:db/add committerid :email/address committer]))]
    tx))

(defn commits
  "Returns log as [[sha msg] ...], in commit order. commit-name may be nil
  or any acceptable commit name arg for git log"
  [commit-name]
  (let [commits (with-open [s (exec-stream (str "git log --pretty=oneline --date-order --reverse " commit-name))]
                  (mapv
                   #(vector (subs % 0 40)
                            (subs % 41 (count %)))
                   (line-seq s)))] 
    commits))

(defn unimported-commits
  [db commit-name]
  (let [imported (into {}
                       (d/q '[:find ?sha ?e
                              :where
                              [?tx :tx/op :import]
                              [?tx :tx/commit ?e]
                              [?e :git/sha ?sha]]
                            db))]
    (pmap commit (remove (fn [[sha _]] (imported sha)) (commits commit-name)))))


(defn ensure-db [db-uri]
  (let [newdb? (d/create-database db-uri)
        conn (d/connect db-uri)]
    (ensure-schema conn)
    conn))

(defn import-git
  [conn repo-uri repo-name commits]
  ;;todo - add already existing commits to new repo if it includes them
  (println "Importing repo:" repo-uri "as:" repo-name)
  (let [db (d/db conn)
        repo
        (or (ffirst (d/q '[:find ?e :in $ ?uri :where [?e :repo/uri ?uri]] db repo-uri))
            (let [temp (d/tempid :db.part/user)
                  tx-ret @(d/transact conn [[:db/add temp :repo/uri repo-uri]])
                  repo (d/resolve-tempid (d/db conn) (:tempids tx-ret) temp)]
              (println "Adding repo" repo-uri)
              repo))]      
    (doseq [commit commits]
      (let [db (d/db conn)]
        (println "Importing commit:" (:sha commit))
        (d/transact conn (commit-tx-data db repo repo-name commit))))
    (d/request-index conn)
    (println "Import complete!")))

(def analyzers [(datomic.codeq.analyzers.clj/impl)])

(defn run-analyzers
  [conn]
  (println "Analyzing...")
  (doseq [a analyzers]
    (let [aname (az/keyname a)
          exts (az/extensions a)
          srevs (set (map first (d/q '[:find ?rev :in $ ?a :where 
                                       [?tx :tx/op :schema]
                                       [?tx :tx/analyzer ?a]
                                       [?tx :tx/analyzerRev ?rev]]
                                     (d/db conn) aname)))]
      (println "Running analyzer:" aname "on" exts)
      ;;install schema(s) if not yet present
      (doseq [[rev aschema] (az/schemas a)]
        (when-not (srevs rev)
          (d/transact conn 
                      (conj aschema {:db/id (d/tempid :db.part/tx)
                                     :tx/op :schema
                                     :tx/analyzer aname
                                     :tx/analyzerRev rev}))))
      (let [db (d/db conn)
            arev (az/revision a)
            ;;candidate files
            cfiles (set (map first (d/q '[:find ?f :in $ [?ext ...] :where
                                          [?fn :file/name ?n]
                                          [(.endsWith ^String ?n ?ext)]
                                          [?node :node/filename ?fn]
                                          [?node :node/object ?f]]
                                        db exts)))
            ;;already analyzed files
            afiles (set (map first (d/q '[:find ?f :in $ ?a ?rev :where
                                          [?tx :tx/op :analyze]
                                          [?tx :tx/analyzer ?a]
                                          [?tx :tx/analyzerRev ?rev]
                                          [?tx :tx/file ?f]]
                                        db aname arev)))]
        ;;find files not yet analyzed
        (doseq [f (sort (clojure.set/difference cfiles afiles))]
          ;;analyze them
          (println "analyzing file:" f " - sha: " (:git/sha (d/entity db f)))
          (let [db (d/db conn)
                src (with-open [s (exec-stream (str "git cat-file -p " (:git/sha (d/entity db f))))]
                      (slurp s))
                adata (try
                        (az/analyze a db f src)
                        (catch Exception ex
                          (println (.getMessage ex))
                          []))]
            (d/transact conn 
                        (conj adata {:db/id (d/tempid :db.part/tx)
                                     :tx/op :analyze
                                     :tx/file f
                                     :tx/analyzer aname
                                     :tx/analyzerRev arev})))))))
  (println "Analysis complete!"))

(defn main [& [db-uri commit]]
  (if db-uri 
      (let [conn (ensure-db db-uri)
            [repo-uri repo-name] (get-repo-uri)]
        ;;(prn repo-uri)
        (import-git conn repo-uri repo-name (unimported-commits (d/db conn) commit))
        (run-analyzers conn))
      (println "Usage: datomic.codeq.core db-uri [commit-name]")))

(defn -main
  [& args]
  (apply main args)
  (shutdown-agents)
  (System/exit 0))


(comment
(def uri "datomic:mem://git")
;;(def uri "datomic:free://localhost:4334/git")
(datomic.codeq.core/main uri "c3bd979cfe65da35253b25cb62aad4271430405c")
(datomic.codeq.core/main uri  "20f8db11804afc8c5a1752257d5fdfcc2d131d08")
(datomic.codeq.core/main uri)
(require '[datomic.api :as d])
(def conn (d/connect uri))
(def db (d/db conn))
(seq (d/datoms db :aevt :file/name))
(seq (d/datoms db :aevt :commit/message))
(seq (d/datoms db :aevt :tx/file))
(count (seq (d/datoms db :aevt :code/sha)))
(take 20 (seq (d/datoms db :aevt :code/text)))
(seq (d/datoms db :aevt :code/name))
(count (seq (d/datoms db :aevt :codeq/code)))
(d/q '[:find ?e :where [?f :file/name "core.clj"] [?n :node/filename ?f] [?n :node/object ?e]] db)
(d/q '[:find ?m :where [_ :commit/message ?m] [(.contains ^String ?m "\n")]] db)
(d/q '[:find ?m :where [_ :code/text ?m] [(.contains ^String ?m "(ns ")]] db)
(sort (d/q '[:find ?var ?def :where [?cn :code/name ?var] [?cq :clj/def ?cn] [?cq :codeq/code ?def]] db))
(sort (d/q '[:find ?var ?def :where [?cn :code/name ?var] [?cq :clj/ns ?cn] [?cq :codeq/code ?def]] db))
(sort (d/q '[:find ?var ?def ?n :where 
             [?cn :code/name ?var] 
             [?cq :clj/ns ?cn]
             [?cq :codeq/file ?f]
             [?n :node/object ?f]
             [?cq :codeq/code ?def]] db))
(def x "(doseq [f (clojure.set/difference cfiles afiles)]
          ;;analyze them
          (println \"analyzing file:\" f)
          (let [db (d/db conn)
                s (with-open [s (exec-stream (str \"git cat-file -p \" (:git/sha (d/entity db f))))]
                    (slurp s))
                adata (az/analyze a db s)]
            (d/transact conn 
                        (conj adata {:db/id (d/tempid :db.part/tx)
                                     :tx/op :analyze
                                     :codeq/file f
                                     :tx/analyzer aname
                                     :tx/analyzerRev arev}))))")
)