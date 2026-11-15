"""Integracao real: pareamento, isolamento da API e estado do receptor."""
import argparse
import json
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--url', required=True, help='Endereço local do receptor, por exemplo http://192.168.1.50:8765')
parser.add_argument('--screen', default='work/pairing-screen.xml')
parser.add_argument('--session-output', default='work/test-session.json')
parser.add_argument('--expected-package', help='Opcional: exija que este pacote apareça na lista de apps')
args = parser.parse_args()
root = ET.parse(args.screen).getroot()
pin = None
for node in root.iter('node'):
    match = re.search(r'conexão(?: manual)?:\s*(\d{3})\s?(\d{3})', node.get('text', ''))
    if match:
        pin = match.group(1) + match.group(2)
assert pin, 'Codigo de conexao nao encontrado na tela da TV'
token = None

def request(path, body=None, authenticated=True, origin=None):
    headers = {}
    if body is not None:
        headers['Content-Type'] = 'application/json'
        headers['Origin'] = origin or args.url
    if token and authenticated:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(args.url + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    data = response.read()
    return response.status, json.loads(data) if 'application/json' in response.headers.get('Content-Type', '') else data

status, _ = request('/api/status')
assert status == 401, 'API deveria exigir pareamento'
status, _ = request('/api/command', {'action': 'home'})
assert status == 401, 'Comandos sem sessao deveriam ser recusados'
wrong = '000000' if pin != '000000' else '111111'
status, _ = request('/api/pair', {'code': wrong}, authenticated=False)
assert status == 409, 'Codigo incorreto foi aceito'
status, pair = request('/api/pair', {'code': pin}, authenticated=False)
assert status == 200 and pair.get('token'), 'Pareamento falhou'
token = pair['token']
status, state = request('/api/status')
assert status == 200 and state['ready'], 'Android ainda nao autorizado'
status, _ = request('/api/command', {'action': 'home'}, origin='https://example.invalid')
assert status == 403, 'Origem externa deveria ser recusada'
status, _ = request('/api/command', {'action': 'not_a_command'})
assert status == 409, 'Comando fora da lista deveria ser recusado'
status, _ = request('/api/command', {'action': 'launch', 'package': "bad;echo injected"})
assert status == 409, 'Pacote invalido deveria ser recusado'
status, apps = request('/api/apps')
assert status == 200 and isinstance(apps, list) and apps, 'Lista real de apps vazia'
if args.expected_package:
    assert any(app['package'] == args.expected_package for app in apps), 'Pacote esperado nao encontrado'
status, _ = request('/')
assert status == 200, 'Pagina embutida indisponivel'
Path(args.session_output).parent.mkdir(parents=True, exist_ok=True)
with open(args.session_output, 'w', encoding='utf-8') as file:
    json.dump({'url': args.url, 'token': token, 'apps': len(apps)}, file)
print(json.dumps({'result': 'passed', 'authenticated': True, 'android_ready': state['ready'], 'installed_apps': len(apps), 'unauthorized_commands_blocked': True, 'foreign_origin_blocked': True, 'unknown_command_blocked': True, 'invalid_package_blocked': True}))
