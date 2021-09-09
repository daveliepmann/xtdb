# 2. Data model

Date: 2021-09-09

## Status

Accepted

## Context

XTDB is a dynamic document database and needs a clearly defined data
model.

## Decision

We will use Apache Arrow https://arrow.apache.org/ which is an
emerging industry standard for columnar data.

Arrow supports nested data via lists and structs, and supports dynamic
typing via unions.

## Consequences

All internal query processing will use Arrow. Persistent storage in
XTDB are all valid Arrow IPC files. Arrow IPC generated by other tools
should be possible to query via XTDB seamlessly and vice versa.