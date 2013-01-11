;;   Copyright (c) Metadata Partners, LLC and Contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns datomic.codeq.analyzer
  (:import [java.io StringReader]
           [org.apache.commons.codec.digest DigestUtils]))

(set! *warn-on-reflection* true)

(defprotocol Analyzer
  (keyname [a] "keyword name for analyzer")
  (revision [a] "long")
  (extensions [a] "[string ...], including '.'")
  (schemas [a] "map of revisions to (incremental) schema data")
  (analyze [a db f src] "f is file entityid, src is string, returns tx-data"))

(defn sha
  "Returns the hex string of the sha1 of s"
  [^String s]
  (org.apache.commons.codec.digest.DigestUtils/shaHex s))

(defn ws-minify
  "Consecutive ws becomes a single space, then trim"
  [s]
  (let [r (java.io.StringReader. s)
        sb (StringBuilder.)]
    (loop [c (.read r) skip true]
      (when-not (= c -1)
        (let [ws (Character/isWhitespace c)]
          (when (or (not ws) (not skip))
            (.append sb (if ws " " (char c))))
          (recur (.read r) ws))))
    (-> sb str .trim)))

(defn loc
  "Returns zero-based [line col endline endcol] given one-based
  \"line col endline endcol\" string"
  [loc-string]
  (mapv dec (read-string (str "[" loc-string "]"))))

(defn line-offsets
  "Returns a vector of zero-based offsets of lines. Note the offsets
   are where the line would be, the last offset is not necessarily
   within the string. i.e. if the last character is a newline, the last
   index is the length of the string."
  [^String s]
  (let [nl (long \newline)]
    (persistent!
     (loop [ret (transient [0]), i 0]
       (if (= i (.length s))
         ret
         (recur (if (= (.codePointAt s i) nl)
                  (conj! ret (inc i))
                  ret)
                (inc i)))))))

(defn segment
  "Given a string and line offsets, returns text from (zero-based)
  line and col to endline/endcol (exclusive)"
  [^String s line-offsets line col endline endcol]
  (subs s
        (+ (nth line-offsets line) col)
        (+ (nth line-offsets endline) endcol)))
