# codeq

**codeq** ('co-deck') is Clojure+Datomic application designed to do code-aware imports of your git repos into a [Datomic](http://datomic.com) db

## Usage

Clone the **codeq** repo. Then (in it) run:

    lein uberjar

Get [Datomic Free](http://www.datomic.com/get-datomic.html)

Unzip it, then start the Datomic Free transactor. Follow the instructions for [running the transactor with the free storage protocol](http://docs.datomic.com/getting-started.html)

    cd theGitRepoYouWantToImport

    java -server -Xmx1g -jar whereverYouPutCodeq/target/codeq-0.1.0-SNAPSHOT-standalone.jar datomic:free://localhost:4334/git

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

You can then (or during) connect to the same db URI with a peer. Or, just start the [Datomic REST service](http://docs.datomic.com/rest.html) and poke around:

    cd whereverYouPutDatomicFree
    bin/rest -p 8080 free datomic:free://localhost:4334/

Browse to [localhost:8080/data/](http://localhost:8080/data/). You should see the `free` storage and the `git` db within it.

The [schema diagram](https://github.com/downloads/Datomic/codeq/codeq.pdf) will help you get oriented.

## More info

See the [intro blog post](http://blog.datomic.com/2012/10/codeq.html) and the [wiki](https://github.com/Datomic/codeq/wiki)

## License

Copyright © 2012 Metadata Partners, LLC and Contributors. All rights reserved.

Distributed under the Eclipse Public License, the same as Clojure.
