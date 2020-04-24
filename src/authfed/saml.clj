(ns authfed.saml
  (:require [clojure.data.xml :refer :all]))

(alias-uri 'samlp "urn:oasis:names:tc:SAML:2.0:protocol")
(alias-uri 'saml "urn:oasis:names:tc:SAML:2.0:assertion")
(alias-uri 'xsi "http://www.w3.org/2001/XMLSchema-instance")
;(alias-uri 'xs "http://www.w3.org/2001/XMLSchema")
;(alias-uri 'ds "http://www.w3.org/2000/09/xmldsig#")

(def request-id (java.util.UUID/randomUUID))
(def response-id (java.util.UUID/randomUUID))
(def assertion-id (java.util.UUID/randomUUID))
(def session-id (java.util.UUID/randomUUID))

(def now (java.time.Instant/now))
(def recent (.minusSeconds now (* 30))) ;; thirty seconds
(def soon (.plusSeconds now (* 5 60))) ;; five minutes
(def later (.plusSeconds now (* 4 60 60))) ;; four hours

(defn saml-response []
 {:tag ::samlp/Response
  :attrs
  {:xmlns/saml "urn:oasis:names:tc:SAML:2.0:assertion"
   :xmlns/samlp "urn:oasis:names:tc:SAML:2.0:protocol"
   :ID (str response-id)
   :Version "2.0"
   :IssueInstant (str now)
   :Destination "http://sp.example.com/demo1/index.php?acs"
   :InResponseTo (str request-id)}
  :content
  [{:tag ::saml/Issuer
    :content ["http://idp.example.com/metadata.php"]}
   {:tag ::samlp/Status
    :content
    [{:tag
      ::samlp/StatusCode
      :attrs {:Value "urn:oasis:names:tc:SAML:2.0:status:Success"}}]}
   {:tag ::saml/Assertion
    :attrs
    {:xmlns/xsi "http://www.w3.org/2001/XMLSchema-instance"
;     :xmlns/xs "http://www.w3.org/2001/XMLSchema"
     :ID (str assertion-id)
     :Version "2.0"
     :IssueInstant (str now)}
    :content
    [{:tag ::saml/Issuer
      :content ["http://idp.example.com/metadata.php"]}
     {:tag ::saml/Subject
      :content
      [{:tag ::saml/NameID
        :attrs
        {:SPNameQualifier "http://sp.example.com/demo1/metadata.php"
         :Format "urn:oasis:names:tc:SAML:2.0:nameid-format:transient"}
        :content ["_ce3d2948b4cf20146dee0a0b3dd6f69b6cf86f62d7"]}
       {:tag ::saml/SubjectConfirmation
        :attrs {:Method "urn:oasis:names:tc:SAML:2.0:cm:bearer"}
        :content
        [{:tag ::saml/SubjectConfirmationData
          :attrs
          {:NotOnOrAfter (str soon)
           :Recipient "http://sp.example.com/demo1/index.php?acs"
           :InResponseTo
           (str request-id)}}]}]}
     {:tag ::saml/Conditions
      :attrs {:NotBefore (str recent)
              :NotOnOrAfter (str soon)}
      :content
      [{:tag ::saml/AudienceRestriction
        :content
        [{:tag ::saml/Audience
          :content ["http://sp.example.com/demo1/metadata.php"]}]}]}
     {:tag ::saml/AuthnStatement
      :attrs {:AuthnInstant (str now)
              :SessionNotOnOrAfter (str later)
              :SessionIndex (str session-id)}
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
          :content ["examplerole1"]}]}]}]}]})
