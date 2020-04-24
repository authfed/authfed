;; https://docs.oracle.com/javase/9/security/java-xml-digital-signature-api-overview-and-tutorial.htm#JSSEC-GUID-E7E9239F-C973-4D05-AC3F-53F714C259DB

(ns user)


(def printdoc
 #(let [trans (.newTransformer (TransformerFactory/newInstance))
        baos (new java.io.ByteArrayOutputStream)]
   (.transform trans (new DOMSource %) (new StreamResult baos))
   (-> baos str)))

(require 'less.awful.ssl)

; (import 'javax.xml.crypto.*)
; (import 'javax.xml.crypto.dsig.*)

(import '[javax.xml.crypto.dsig DigestMethod Transform XMLSignatureFactory CanonicalizationMethod])
; (import 'javax.xml.crypto.dom.*)
(import '[javax.xml.crypto.dsig.dom DOMSignContext])
; (import 'javax.xml.crypto.dsig.keyinfo.*)
(import '[javax.xml.crypto.dsig.spec C14NMethodParameterSpec])

(import 'java.io.FileInputStream)
(import 'java.io.FileOutputStream)
(import 'java.io.OutputStream)
; (import 'java.security.*)
(import 'java.util.Collections)
(import 'java.util.Iterator)
(import 'javax.xml.XMLConstants)
(import 'javax.xml.parsers.DocumentBuilderFactory)
(import 'javax.xml.transform.TransformerFactory)
(import 'javax.xml.transform.dom.DOMSource)
(import 'javax.xml.transform.stream.StreamResult)
(import '[javax.xml.validation Schema SchemaFactory Validator])
(import 'org.w3c.dom.Document)

;; Create a DOM XMLSignatureFactory that will be used to generate the
;; enveloped signature
(def fac (XMLSignatureFactory/getInstance "DOM"))

;; Create a Reference to the enveloped document (in this case we are
;; signing the whole document, so a URI of "" signifies that) and
;; also specify the SHA1 digest algorithm and the ENVELOPED Transform.
(def docuref
  (.newReference fac
    (str "#" authfed.saml/assertion-id)
    (.newDigestMethod fac DigestMethod/SHA1 nil)
    (Collections/singletonList (.newTransform fac Transform/ENVELOPED nil))
    nil
    nil))

;; Create the SignedInfo
(def si
  (.newSignedInfo fac
    (.newCanonicalizationMethod fac CanonicalizationMethod/EXCLUSIVE nil)
    (.newSignatureMethod fac "http://www.w3.org/2000/09/xmldsig#rsa-sha1" nil)
    (Collections/singletonList docuref)))

(def kp
  (less.awful.ssl/key-pair
    (less.awful.ssl/public-key "dummy-public.pem")
    (less.awful.ssl/private-key "dummy-private.pem")))

;; Create a KeyValue containing the DSA PublicKey that was generated
(def kif (.getKeyInfoFactory fac))
(def kv (.newKeyValue kif (.getPublic kp)))

;; Create a KeyInfo and add the KeyValue to it
(def ki (.newKeyInfo kif (Collections/singletonList kv)))

(defonce schema
 (let [schemaFactory (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)]
  (.newSchema schemaFactory (new java.net.URL "http://docs.oasis-open.org/security/saml/v2.0/saml-schema-protocol-2.0.xsd"))))

;; Instantiate the document to be signed
(def dbf
 (doto (DocumentBuilderFactory/newInstance)
  (.setNamespaceAware true)
  (.setSchema schema)))

; (def docu
;  (-> dbf
;      (.newDocumentBuilder)
;      (.parse (java.io.StringBufferInputStream. "
; <ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">
;   <ds:SignedInfo><ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"/>
;     <ds:SignatureMethod Algorithm=\"http://www.w3.org/2000/09/xmldsig#rsa-sha1\"/>
;   <ds:Reference URI=\"#pfx92551a8c-fb75-d101-3b90-0c10decd0cfd\"><ds:Transforms><ds:Transform Algorithm=\"http://www.w3.org/2000/09/xmldsig#enveloped-signature\"/><ds:Transform Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"/></ds:Transforms><ds:DigestMethod Algorithm=\"http://www.w3.org/2000/09/xmldsig#sha1\"/><ds:DigestValue>Ce09qG1evimyJM9gho4hfnGEMVA=</ds:DigestValue></ds:Reference></ds:SignedInfo><ds:SignatureValue>ZYL+qLfxxxddyHvAE6a4rbhCPGXk2JPSCvTRx7vgRtGlW1AscRMlmvDKzJlJa9Xx7GYSfnS+BhxjRoIm1Q0DzBiFeY9Jndkf1836EpcBMclTrn0Q6AtZHcUR/enWBSb/JmUWPS7QLc5/yhWi5SE2nrwuQbAcJZD2GM08PxG2aZs=</ds:SignatureValue>
; <ds:KeyInfo><ds:X509Data><ds:X509Certificate>MIICajCCAdOgAwIBAgIBADANBgkqhkiG9w0BAQ0FADBSMQswCQYDVQQGEwJ1czETMBEGA1UECAwKQ2FsaWZvcm5pYTEVMBMGA1UECgwMT25lbG9naW4gSW5jMRcwFQYDVQQDDA5zcC5leGFtcGxlLmNvbTAeFw0xNDA3MTcxNDEyNTZaFw0xNTA3MTcxNDEyNTZaMFIxCzAJBgNVBAYTAnVzMRMwEQYDVQQIDApDYWxpZm9ybmlhMRUwEwYDVQQKDAxPbmVsb2dpbiBJbmMxFzAVBgNVBAMMDnNwLmV4YW1wbGUuY29tMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDZx+ON4IUoIWxgukTb1tOiX3bMYzYQiwWPUNMp+Fq82xoNogso2bykZG0yiJm5o8zv/sd6pGouayMgkx/2FSOdc36T0jGbCHuRSbtia0PEzNIRtmViMrt3AeoWBidRXmZsxCNLwgIV6dn2WpuE5Az0bHgpZnQxTKFek0BMKU/d8wIDAQABo1AwTjAdBgNVHQ4EFgQUGHxYqZYyX7cTxKVODVgZwSTdCnwwHwYDVR0jBBgwFoAUGHxYqZYyX7cTxKVODVgZwSTdCnwwDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQ0FAAOBgQByFOl+hMFICbd3DJfnp2Rgd/dqttsZG/tyhILWvErbio/DEe98mXpowhTkC04ENprOyXi7ZbUqiicF89uAGyt1oqgTUCD1VsLahqIcmrzgumNyTwLGWo17WDAa1/usDhetWAMhgzF/Cnf5ek0nK00m0YZGyc4LzgD0CROMASTWNg==</ds:X509Certificate></ds:X509Data></ds:KeyInfo></ds:Signature>
; "))))

(def docu
 (.parse (.newDocumentBuilder dbf)
  (java.io.StringBufferInputStream. (clojure.data.xml/emit-str (authfed.saml/saml-response)))))

;; Create a DOMSignContext and specify the DSA PrivateKey and
;; location of the resulting XMLSignature's parent element
(def dsc
 (let [assertion-node (.item (.getElementsByTagName (.getChildNodes (.getDocumentElement docu)) "saml:Assertion") 0)]
  (new DOMSignContext
   (.getPrivate kp)
   assertion-node
   (.item (.getElementsByTagName assertion-node "saml:Subject") 0))))

;; Create the XMLSignature (but don't sign it yet)
(def signature (.newXMLSignature fac si ki))

;; Marshal, generate (and sign) the enveloped signature
(.sign signature dsc)

; (def trans (.newTransformer (TransformerFactory/newInstance)))
; (def baos (new java.io.ByteArrayOutputStream))
; (.transform trans (new DOMSource docu) (new StreamResult baos))

;; (-> baos str println)

; (spit (str authfed.saml/assertion-id ".xml") (printdoc docu))

; (def validator (.newValidator schema))
; (.validate validator (new DOMSource docu))
