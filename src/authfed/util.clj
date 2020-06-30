(ns authfed.util
 (:import [java.security MessageDigest]))

;; thanks to noisesmith from the comment section
;; https://gist.github.com/jizhang/4325757#gistcomment-1993162
(defn md5 [s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm (.getBytes s))]
    (format "%032x" (BigInteger. 1 raw))))
