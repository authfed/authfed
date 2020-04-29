#!/bin/bash
# Invoke this bash script as the root user to create and run a bash script which copies PEM files to the /etc/authfed directory.
TMP=`mktemp`
find -L /etc/letsencrypt/live -type f -name '*.pem' | awk -F/ '{print "cp -v " $0 " /etc/authfed/" $5 "-" $6}' > $TMP
echo 'chown -v authfed.authfed /etc/authfed/*.pem' >> $TMP
echo 'chmod -v 0640 /etc/authfed/*.pem' >> $TMP
bash $TMP
rm -fv $TMP
