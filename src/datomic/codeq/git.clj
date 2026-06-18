;;   Copyright (c) Metadata Partners, LLC and Contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns datomic.codeq.git
  (:require [clojure.string :as string])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [org.eclipse.jgit.lib Repository ObjectId FileMode PersonIdent Constants]
           [org.eclipse.jgit.storage.file FileRepositoryBuilder]
           [org.eclipse.jgit.revwalk RevWalk RevCommit RevSort]
           [org.eclipse.jgit.treewalk TreeWalk]))

(set! *warn-on-reflection* true)

(defn ^Repository open-repo
  "Open the git repository discovered from dir (defaults to cwd)."
  ([] (open-repo (System/getProperty "user.dir")))
  ([dir]
   (-> (FileRepositoryBuilder.)
       (.findGitDir (File. ^String (str dir)))
       (.setMustExist true)
       (.readEnvironment)
       (.build))))

(defn ^String blob-text
  "UTF-8 text of the blob named by the 40-char hex sha."
  [^Repository repo ^String sha]
  (let [reader (.newObjectReader repo)]
    (try
      (let [loader (.open reader (ObjectId/fromString sha))]
        (String. (.getBytes loader) StandardCharsets/UTF_8))
      (finally (.close reader)))))
