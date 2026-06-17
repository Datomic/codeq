# codeq

**codeq** ('co-deck') is Clojure+Datomic application designed to do code-aware imports of your git repos into a [Datomic](http://datomic.com) db

## Usage

Clone the **codeq** repo. Then, with the [Clojure CLI](https://clojure.org/guides/install_clojure) installed:

Build a standalone jar:

    clojure -T:build uber

`codeq` uses [Datomic Pro](https://www.datomic.com/) (free, no license required) via the Peer API. For a quick run with no transactor, use the in-memory `datomic:mem://` storage:

    cd theGitRepoYouWantToImport
    java -jar whereverYouPutCodeq/target/codeq.jar datomic:mem://git

For development without building a jar:

    clojure -M:run datomic:mem://git

For persistent storage, start a Datomic Pro transactor and pass its URI (e.g. `datomic:dev://localhost:4334/git`) instead of `datomic:mem://`.

This will create a db called `git` (you can call it whatever you like) and import the commits from the local view of the repo. You should see output like:

    Importing repo: git@github.com:clojure/clojure.git as: clojure
    Adding repo git@github.com:clojure/clojure.git
    Importing commit: e54a1ff1ac0d02560e80aad460e77ac353efad49
    Importing commit: 894a0c81075b8f4b64b7f890ab0c8522a7a9986a
    ...
    Importing commit: c1884eaca8ffb7aff2c3d393a9d5fa3306cf3f33
    Importing commit: 01b4cb7156f0b378e70020d0abe293bffe35b031
    Importing commit: 6bbfd943766e11e52a3fe21b177d55536892d132
    Import complete!

    Analyzing...
    Running analyzer: :clj on [.clj]
    analyzing file: 17592186045504
    analyzing file: 17592186045496
    Analysis complete!

The import is not too peppy, since it shells to `git` relentlessly, but it imports e.g. Clojure's entire commit history in about 10 minutes, plus analysis.

You can import more than one repo into the same db. You can re-import later after some more commits and they will be incrementally added.

You can then (or during) connect to the same db URI with a peer. Note that the in-memory `datomic:mem://` db only lives inside the importing process — to browse a db after import, run against a persistent storage URI backed by a Datomic Pro transactor (e.g. `datomic:dev://localhost:4334/git`).

The [schema diagram](https://github.com/downloads/Datomic/codeq/codeq.pdf) will help you get oriented.

## More info

See the [intro blog post](http://blog.datomic.com/2012/10/codeq.html) and the [wiki](https://github.com/Datomic/codeq/wiki)

## License

Copyright © 2012 Metadata Partners, LLC and Contributors. All rights reserved.

Distributed under the Eclipse Public License, the same as Clojure.
