import json
import urllib.request
from pathlib import Path
session = json.loads(Path('work/test-session.json').read_text(encoding='utf-8'))
text = 'Olá, mãe! Ação e café ☕'
request = urllib.request.Request(session['url'] + '/api/command', data=json.dumps({'action':'text','text':text}).encode(), headers={'Content-Type':'application/json','Origin':session['url'],'Authorization':'Bearer '+session['token']})
with urllib.request.urlopen(request, timeout=15) as response:
    result = json.load(response)
assert result.get('ok'), result
Path('work/expected-text.txt').write_text(text, encoding='utf-8')
print('Texto Unicode enviado pela API HTTP do receptor.')
