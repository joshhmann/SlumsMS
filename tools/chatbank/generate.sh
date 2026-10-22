#!/bin/sh
# Ticket 59: the chat line bank generator (see README.md). Runs from the repo root with the built server jar.
set -e
cd "$(dirname "$0")/../.."
JAR=target/Cosmic.jar
[ -f "$JAR" ] || { echo "build the server jar first: mvn -o -q package -DskipTests"; exit 1; }
exec java -cp "$JAR" soloMapling.ArtificialPlayer.BotConvo.ChatBankGen "$@"
