# Gemini [![Build Status](https://travis-ci.org/src-d/gemini.svg?branch=master)](https://travis-ci.org/src-d/gemini) [![codecov](https://codecov.io/gh/src-d/gemini/branch/master/graph/badge.svg)](https://codecov.io/gh/src-d/gemini)
> Find similar code in Git repositories

Gemini is a tool for searching for similar 'items' in source code repositories.
Supported granularity level or items are:
 - repositories (TBD)
 - files
 - functions (TBD)

## Run

```
./hash   <path-to-repos-or-siva-files>
./query  <path-to-file>
./report
```

You would need to prefix commands with `docker-compose exec gemini` if you run it in docker. Read below how to start gemini in docker or standalone mode.

### Hash
To pre-process number of repositories for a quick finding of the duplicates run

```
./hash ./src/test/resources/siva
```

Input format of the repositories is the same as in [src-d/Engine](https://github.com/src-d/engine).

### Query
To find all duplicate of the single file run

```
./query <path-to-single-file>
```

There is an example of a client in a Golang under [src/main/go](src/main/go)

### Report
To find all duplicate files in all repositories run

```
./report
```

All repositories must be [hashed](#hash) before.

## Requirements

### Docker

Start containers:

```bash
docker-compose up -d
```

Local directories `repositories` and `query` are available as `/repositories` and `/query` inside the container.

Examples:

```bash
$ docker-compose exec gemini ./hash /repositories
Hashing 2 repositories in: /repositories
	file:/repositories/f281ab6f2e0e38dcc3af05360667d8f530c00103.siva
	file:/repositories/9279be3cf07fb3cca4fc964b27acea57e0af461b.siva
Done

$ docker-compose exec gemini ./query /query/consumer.go
Query duplicate files of: /query/consumer.go
Duplicates of /query/consumer.go:
	https://github.com/src-d/borges/blob/e784f9d5f59d5c081c5f8f71b6c517918b899df0/consumer.go
Similar files of /query/consumer.go:
	https://github.com/erizocosmico/borges/blob/b1fcd3bf0ba810c05cb418babc09cc7f7783cc03/consumer.go

$ docker-compose exec gemini ./report
2 duplicates:
	https://github.com/erizocosmico/borges/blob/b1fcd3bf0ba810c05cb418babc09cc7f7783cc03/model_test.go
	https://github.com/src-d/borges/blob/e784f9d5f59d5c081c5f8f71b6c517918b899df0/model_test.go
...
2 similar files:
	https://github.com/erizocosmico/borges/blob/b1fcd3bf0ba810c05cb418babc09cc7f7783cc03/consumer_test.go
	https://github.com/src-d/borges/blob/e784f9d5f59d5c081c5f8f71b6c517918b899df0/consumer_test.go
...
```


### Standalone

You would need:

 - JVM 1.8
 - Apache Cassandra or ScyllaDB
 - Apache Spark
 - Python 3
 - Go (optional)

By default, all commands are going to use
 - **Apache Cassandra or ScyllaDB** instance available at `localhost:9042`
 - **Apache Spark**, available though `$SPARK_HOME`

```bash
# save some repos in .siva files using Borges
echo -e "https://github.com/src-d/borges.git\nhttps://github.com/erizocosmico/borges.git" > repo-list.txt

# get Borges from https://github.com/src-d/borges/releases
borges pack --loglevel=debug --workers=2 --to=./repos -f repo-list.txt

# start Apache Cassandra
docker run -p 9042:9042 \
  --name cassandra -d rinscy/cassandra:3.11

# or ScyllaDB \w workaround https://github.com/gocql/gocql/issues/987
docker run -p 9042:9042 --volume $(pwd)/scylla:/var/lib/scylla \
  --name some-scylla -d scylladb/scylla:2.0.0 \
  --broadcast-address 127.0.0.1 --listen-address 0.0.0.0 --broadcast-rpc-address 127.0.0.1 \
  --memory 2G --smp 1

# to get access to DB for development
docker exec -it some-scylla cqlsh
```


### External Apache Spark cluster
Just set url to the Spark Master though env var
```
MASTER="spark://<spark-master-url>" ./hash <path>
```

### CLI arguments

```
 --db <url to Cassandra>, default localhost:9042
```

# Dev

Build, fatJar for Apache Spark (hash, report)
```
./sbt assembly
```

Build, for query
```
./sbt package
```

Tests (with embedded Cassandra)
```
./sbt test
```

To generate gRPC code for Feature Extractors from `src/main/proto/*.proto` files:
```
./src/main/resources/generate_from_proto.sh
```

To generate protobuf messages fixtures you may use [bblfsh-sdk-tools](https://github.com/bblfsh/sdk):
```
bblfsh-sdk-tools fixtures -p .proto -l <LANG> <path-to-source-code-file>
```

## License

Copyright (C) 2017 source{d}.
This project is licensed under the [GNU General Public License v3.0](LICENSE).
