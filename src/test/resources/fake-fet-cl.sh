#!/bin/bash
# Stand-in for fet-cl in tests. Reproduces the file layout, log files and stdout markers of the real binary.
# The outcome is chosen with FAKE_FET_OUTCOME: success (default), impossible, timeexceeded, slow, badargs, dataerror.

for a in "$@"; do
  case "$a" in
    --version) echo "FET version 7.10.4"; exit 0 ;;
    --inputfile=*) IN="${a#*=}" ;;
    --outputdir=*) OUT="${a#*=}" ;;
    --timelimitseconds=*) TL="${a#*=}" ;;
  esac
done
if [ -z "$IN" ] || [ -z "$OUT" ]; then
  echo "Incorrect command-line parameters (missing --inputfile or --outputdir)."
  exit 1
fi
STEM=$(basename "$IN" .fet)
LOGS="$OUT/logs"
mkdir -p "$LOGS"
printf '%s\n' "$@" > "$LOGS/args.txt"
: > "$LOGS/warnings.txt"
: > "$LOGS/errors.txt"
PROGRESS="$LOGS/max_placed_activities.txt"
printf 'This is the list of max placed activities, chronologically. If FET could reach maximum n-th activity, look at the n+1-st activity in the initial order of the activities\n\n' > "$PROGRESS"
RESULT="$LOGS/result.txt"
: > "$RESULT"

say() { echo "$1"; echo "$1" >> "$RESULT"; }

FIRST_DAY=$(awk '/<Days_List>/{d=1} d && /<Name>/{sub(/.*<Name>/,""); sub(/<\/Name>.*/,""); print; exit}' "$IN")
FIRST_HOUR=$(awk '/<Hours_List>/{h=1} h && /<Name>/{sub(/.*<Name>/,""); sub(/<\/Name>.*/,""); print; exit}' "$IN")
IDS=$(awk '/<Activities_List>/{a=1} /<\/Activities_List>/{a=0} a && /<Id>/{sub(/.*<Id>/,""); sub(/<\/Id>.*/,""); print}' "$IN")
COUNT=$(echo "$IDS" | grep -c .)
# FET assigns a room when the file has one. Use the first real room so lock/unlock has something to work with.
ROOM=$(awk '/<Rooms_List>/{r=1} /<\/Rooms_List>/{r=0} r && /<Name>/ && !done{sub(/.*<Name>/,""); sub(/<\/Name>.*/,""); print; done=1}' "$IN")

write_activities() { # $1 = dir, $2 = how many to place
  local dir="$1" n="$2" i=0
  mkdir -p "$dir"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<Activities_Timetable>'
    for id in $IDS; do
      i=$((i+1))
      if [ "$i" -le "$n" ]; then
        echo "  <Activity><Id>$id</Id><Day>$FIRST_DAY</Day><Hour>$FIRST_HOUR</Hour><Room>$ROOM</Room></Activity>"
      else
        echo "  <Activity><Id>$id</Id><Day></Day><Hour></Hour><Room></Room></Activity>"
      fi
    done
    echo '</Activities_Timetable>'
  } > "$dir/${STEM}_activities.xml"
  {
    echo "Soft conflicts of $STEM"
    [ "$n" -lt "$COUNT" ] && echo "Warning! Only $n out of $COUNT activities placed!"
    echo "Generated with FET 7.10.4 on 2026-09-07"
    echo
    echo "Number of broken soft constraints: 1"
    echo "Total soft conflicts: 12.50"
    echo
    echo "Soft conflicts list (in descending order):"
    echo
    echo "Time constraint min days between activities broken: activity with id=1 (T1 S 9A) conflicts with activity with id=2 (T1 S 9A), being 0 days too close - this increases the conflicts total by 12.5"
    echo
    echo "End of file."
  } > "$dir/${STEM}_soft_conflicts.txt"
  echo "<html><body>$STEM</body></html>" > "$dir/${STEM}_index.html"
  echo "<html></html>" > "$dir/${STEM}_teachers_days_horizontal.html"
}

write_full() {
  write_activities "$OUT/timetables/$STEM" "$COUNT"
  cp "$IN" "$OUT/timetables/$STEM/${STEM}_data_and_timetable.fet"
}

write_partial() {
  write_activities "$OUT/timetables/$STEM-current" 1
  write_activities "$OUT/timetables/$STEM-highest" 1
  SECOND=$(echo "$IDS" | sed -n '2p')
  FIRST=$(echo "$IDS" | sed -n '1p')
  {
    echo "Here are the placed activities which led to an inconsistency, in order, from the first one to the last one (the last one FET failed to schedule and the last ones are most likely the difficult ones):"
    echo
    echo "No: 1, Id: $FIRST (Teacher: T1, Subject: Math, Students: 9A)"
    echo "No: 2, Id: ${SECOND:-$FIRST} (Teacher: T1, Subject: Math, Students: 9A)"
  } > "$LOGS/difficult_activities.txt"
}

case "${FAKE_FET_OUTCOME:-success}" in
  badargs)
    echo "Incorrect command-line parameters (Unrecognized option: --bogus)."
    exit 1 ;;
  dataerror)
    say "Cannot precompute - data is wrong - aborting"
    printf 'Title: FET warning\nMessage: Activity with id=1 (T1 Math 9A) has no allowed slot - please correct that.\n\n' >> "$LOGS/errors.txt"
    exit 1 ;;
  success)
    say "Starting timetable generation..."
    echo "At time 0 h 0 m 1 s, FET reached $COUNT activities placed" >> "$PROGRESS"
    write_full
    say "Generation successful"
    exit 0 ;;
  impossible)
    say "Starting timetable generation..."
    echo "At time 0 h 0 m 1 s, FET reached 1 activities placed" >> "$PROGRESS"
    write_partial
    say "Impossible"
    exit 0 ;;
  timeexceeded)
    say "Starting timetable generation..."
    sleep "${TL:-1}"
    write_partial
    say "Time exceeded"
    exit 0 ;;
  slow)
    say "Starting timetable generation..."
    trap 'write_partial; say "Generation interrupted"; exit 0' TERM
    trap 'exit 130' INT
    for i in $(seq 1 120); do
      echo "At time 0 h 0 m $i s, FET reached $i activities placed" >> "$PROGRESS"
      sleep 1
    done
    write_full
    say "Generation successful"
    exit 0 ;;
esac
