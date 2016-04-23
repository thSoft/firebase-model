#!/usr/bin/env bash

REPOSITORY=firebase-scalajs
git clone https://github.com/thSoft/$REPOSITORY.git
$REPOSITORY/scripts/setup.sh
$REPOSITORY/scripts/build.sh
rm -rf $REPOSITORY

sbt eclipse
