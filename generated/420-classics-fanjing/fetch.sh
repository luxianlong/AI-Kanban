set -u
UA="DianjiArchiveBot/1.0 (https://youraikanban.com/p/dianji.html; luxianlongmicrosoft@gmail.com)"
while read -r t; do
  fn="pages/$(echo "$t" | sed 's#/#__#g').json"
  if [ -s "$fn" ] && [ "$(stat -c%s "$fn")" -gt 200 ]; then continue; fi
  enc=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$t")
  curl -s --max-time 60 -A "$UA" "https://zh.wikisource.org/w/api.php?action=parse&page=$enc&prop=wikitext&format=json&formatversion=2" -o "$fn"
  echo "$t $(stat -c%s "$fn")"
  sleep 0.2
done < titles.txt
