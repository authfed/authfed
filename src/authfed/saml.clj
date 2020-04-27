(ns authfed.saml
  (:require [less.awful.ssl]
            [authfed.config :as config]
            [clojure.data.xml :refer :all])
  (:import [javax.xml.crypto.dsig DigestMethod Transform XMLSignatureFactory CanonicalizationMethod]
           [javax.xml.crypto.dsig.dom DOMSignContext]
           [javax.xml.crypto.dsig.spec C14NMethodParameterSpec TransformParameterSpec]
           [java.io ByteArrayOutputStream StringBufferInputStream]
           [java.util Collections]
           [javax.xml XMLConstants]
           [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.transform TransformerFactory]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamResult]
           [javax.xml.validation Schema SchemaFactory Validator]
           [org.w3c.dom Document]))

(alias-uri 'samlp "urn:oasis:names:tc:SAML:2.0:protocol")
(alias-uri 'saml "urn:oasis:names:tc:SAML:2.0:assertion")
(alias-uri 'xsi "http://www.w3.org/2001/XMLSchema-instance")
;(alias-uri 'xs "http://www.w3.org/2001/XMLSchema")
;(alias-uri 'ds "http://www.w3.org/2000/09/xmldsig#")

(def idp-entityid "https://authfed.net") ;; IdP EntityId
(def sp-entityid "https://signin.aws.amazon.com/saml") ;; SP EntityId
(def sp-endpoint "https://signin.aws.amazon.com/saml") ;; SP Attribute Consume Service Endpoint
(def target-url "https://signin.aws.amazon.com/saml") ;; Target URL, Destination of the Response

(defn saml-response []
 (let [request-id (str "request-" (java.util.UUID/randomUUID))
       response-id (str "response-" (java.util.UUID/randomUUID))
       assertion-id (str "assertion-" (java.util.UUID/randomUUID))
       session-id (str "session-" (java.util.UUID/randomUUID))
       inst (java.time.Instant/now)
       now (str inst)
       recent (str (.minusSeconds inst (* 30)))
       soon (str (.plusSeconds inst (* 5 60)))
       later (str (.plusSeconds inst (* 4 60 60)))]
  {:tag ::samlp/Response
   :attrs
   {:xmlns/saml "urn:oasis:names:tc:SAML:2.0:assertion"
    :xmlns/samlp "urn:oasis:names:tc:SAML:2.0:protocol"
    :ID response-id
    :Version "2.0"
    :IssueInstant now
    :Destination target-url
    :InResponseTo request-id}
   :content
   [{:tag ::samlp/Status
     :content
     [{:tag
       ::samlp/StatusCode
       :attrs {:Value "urn:oasis:names:tc:SAML:2.0:status:Success"}}]}
    {:tag ::saml/Assertion
     :attrs
     {:xmlns/xsi "http://www.w3.org/2001/XMLSchema-instance"
      :xmlns/xs "http://www.w3.org/2001/XMLSchema"
      :ID assertion-id
      :Version "2.0"
      :IssueInstant now}
     :content
     [{:tag ::saml/Issuer
       :content [idp-entityid]}
      {:tag ::saml/Subject
       :content
       [{:tag ::saml/NameID
         :attrs
         {:SPNameQualifier sp-entityid
          :Format "urn:oasis:names:tc:SAML:2.0:nameid-format:transient"}
         :content ["_ce3d2948b4cf20146dee0a0b3dd6f69b6cf86f62d7"]}
        {:tag ::saml/SubjectConfirmation
         :attrs {:Method "urn:oasis:names:tc:SAML:2.0:cm:bearer"}
         :content
         [{:tag ::saml/SubjectConfirmationData
           :attrs
           {:NotOnOrAfter soon
            :Recipient sp-endpoint
            :InResponseTo
            request-id}}]}]}
      {:tag ::saml/Conditions
       :attrs {:NotBefore recent
               :NotOnOrAfter soon}
       :content
       [{:tag ::saml/AudienceRestriction
         :content
         [{:tag ::saml/Audience
           :content [sp-entityid]}]}]}
      {:tag ::saml/AuthnStatement
       :attrs {:AuthnInstant now
               :SessionNotOnOrAfter later
               :SessionIndex session-id}
       :content
       [{:tag ::saml/AuthnContext
         :content
         [{:tag ::saml/AuthnContextClassRef
           :content ["urn:oasis:names:tc:SAML:2.0:ac:classes:Password"]}]}]}
      {:tag ::saml/AttributeStatement
       :content
       [{:tag ::saml/Attribute
         :attrs {:Name "uid"
                 :NameFormat "urn:oasis:names:tc:SAML:2.0:attrname-format:basic"}
         :content
         [{:tag ::saml/AttributeValue
           :attrs #::xsi{:type "xs:string"}
           :content ["test"]}]}
        {:tag ::saml/Attribute
         :attrs
         {:Name "mail"
          :NameFormat "urn:oasis:names:tc:SAML:2.0:attrname-format:basic"}
         :content
         [{:tag ::saml/AttributeValue
           :attrs #::xsi{:type "xs:string"}
           :content ["test@example.com"]}]}
        {:tag ::saml/Attribute
         :attrs {:Name "https://aws.amazon.com/SAML/Attributes/RoleSessionName"}
         :content
         [{:tag ::saml/AttributeValue
           :attrs #::xsi{:type "xs:string"}
           :content ["test@example.com"]}]}
        {:tag ::saml/Attribute
         :attrs {:Name "https://aws.amazon.com/SAML/Attributes/Role"}
         :content
         [{:tag ::saml/AttributeValue
           :attrs #::xsi{:type "xs:string"}
           :content [(::config/saml-role-mapping config/params)]}]}
        {:tag ::saml/Attribute
         :attrs
         {:Name "eduPersonAffiliation"
          :NameFormat "urn:oasis:names:tc:SAML:2.0:attrname-format:basic"}
         :content
         [{:tag ::saml/AttributeValue
           :attrs #::xsi{:type "xs:string"}
           :content ["users"]}
          {:tag ::saml/AttributeValue
           :attrs
            #::xsi{:type "xs:string"}
           :content ["examplerole1"]}]}]}]}]}))

(defonce schema
 (let [schemaFactory (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)]
  (.newSchema schemaFactory (new java.net.URL "http://docs.oasis-open.org/security/saml/v2.0/saml-schema-protocol-2.0.xsd"))))

(def dbf
 (doto (DocumentBuilderFactory/newInstance)
  (.setNamespaceAware true)
  (.setSchema schema)))

(def fac (XMLSignatureFactory/getInstance "DOM"))

(def kp
  (less.awful.ssl/key-pair
    (less.awful.ssl/public-key "dummy-public.pem")
    (less.awful.ssl/private-key "dummy-private.pem")))

(def kif (.getKeyInfoFactory fac))
(def kv (.newKeyValue kif (.getPublic kp)))

(def ki (.newKeyInfo kif (Collections/singletonList kv)))

(defn sign-and-serialize [data]
 (let [assertion-id (-> data :content second :attrs :ID)
       docu (.parse (.newDocumentBuilder dbf)
             (new StringBufferInputStream (emit-str data)))
       docuref (.newReference fac
                 (str "#" assertion-id)
                 (.newDigestMethod fac DigestMethod/SHA1 nil)
                 (Collections/singletonList (.newTransform fac Transform/ENVELOPED nil))
                 nil
                 nil)
       si (.newSignedInfo fac
            (.newCanonicalizationMethod fac CanonicalizationMethod/EXCLUSIVE nil)
            (.newSignatureMethod fac "http://www.w3.org/2000/09/xmldsig#rsa-sha1" nil)
            (Collections/singletonList docuref))
       dsc (let [parent (.item (.getElementsByTagName (.getChildNodes (.getDocumentElement docu)) "saml:Assertion") 0)
                 sibling (.item (.getElementsByTagName parent "saml:Subject") 0)]
            (new DOMSignContext
             (.getPrivate kp)
             parent
             sibling))
       signature (.newXMLSignature fac si ki)
       validator (.newValidator schema)]
  (.sign signature dsc)
  (.validate validator (new DOMSource docu))
  (let [trans (.newTransformer (TransformerFactory/newInstance))
        baos (new ByteArrayOutputStream)]
   (.transform trans (new DOMSource docu) (new StreamResult baos))
   (str baos))))

(comment

(ns authfed.saml)
(use 'clojure.repl 'clojure.pprint 'clojure.java.javadoc)
(println (sign-and-serialize (saml-response)))

(require 'ring.util.codec)
(let [samlresponse (sign-and-serialize (saml-response))] (spit "test.html" (str "<form method=\"POST\" action=\"https://signin.aws.amazon.com/saml\"><input type=\"hidden\" name=\"SAMLResponse\" value=" (ring.util.codec/base64-encode (.getBytes samlresponse)) " /><input type=\"submit\" value=\"Submit\" /></form>")))
;; open -a /Applications/Safari.app

)
