# Authfed

## Getting started

Start a REPL using `clj -A:dev` then run:

```clj
(ns authfed.server)
(use 'clojure.repl 'clojure.pprint 'clojure.java.javadoc)
(load-file "src/authfed/server.clj")
(http/start runnable)
```

And then point your web browser to http://localhost:8080/

## Socket Repl

I recommend adding something like this to your ~/.clojure/deps.edn file under {:aliases {:socket ... }}

```edn
{:jvm-opts ["-Dclojure.server.repl={:address,\"127.0.0.1\",:port,50505,:accept,clojure.core.server/repl}"]}
```

## Logging

To learn more about configuring Logback see http://logback.qos.ch/documentation.html

## License

Eclipse Public License v1.0, see LICENSE file.
