#!/bin/bash
# record.sh <keyboard> [walks] [keys]: fetch the keyboard's package and record KeymanWeb walks into $KEYMAN_ORACLE_WORK/oracle/<keyboard>/. Needs oracle.mjs built with build.mjs in $KEYMAN_ORACLE_WORK/oracle-build; see ../PROVENANCE.md.
id=$1; W=${2:-20}; N=${3:-40}
S=${KEYMAN_ORACLE_WORK:?set KEYMAN_ORACLE_WORK}
out=$S/oracle/$id; mkdir -p $out
if [ ! -f $out/$id.kmx ] || [ ! -f $out/$id.js ]; then
  read ver pkg < <(curl -s "https://api.keyman.com/keyboard/$id" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('version',''),d.get('packageFilename',''))" 2>/dev/null)
  [ -z "$pkg" ] && { echo "$id: no package"; exit 1; }
  tmp=$(mktemp -d); curl -sfL -o $tmp/p.kmp "https://downloads.keyman.com/keyboards/$id/$ver/$pkg" || { echo "$id: download failed"; rm -rf $tmp; exit 1; }
  unzip -o -q $tmp/p.kmp "$id.kmx" "$id.js" -d $out 2>/dev/null; rm -rf $tmp
  [ -f $out/$id.js ] || { echo "$id: no js"; exit 1; }
  [ -f $out/$id.kmx ] || { echo "$id: no kmx"; exit 1; }
fi
cd $S/oracle-build && node oracle.mjs $out/$id.js $out $W $N $RANDOM >/dev/null 2>$out/oracle.err || { echo "$id: oracle failed $(head -c 200 $out/oracle.err)"; exit 1; }
echo "$id ok"
