#!/usr/bin/env python3
"""Real local API/PostgreSQL smoke tests. AI transport alone uses a test fixture.

Run a separate application process with verification-only API keys. This script
never changes .env, never contacts a real model provider, and uses new test users.
"""
from __future__ import annotations
import argparse
import contextlib
import http.cookiejar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

BUNDLED_PYTHON = '/Users/zhengbowen/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3'
MODELS: list[str] = []
TOOL_ROUNDS: list[dict] = []

class ModelFixture(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_POST(self):
        if self.headers.get('Authorization') != 'Bearer verification-only':
            self.send_error(401, 'Start the test backend with verification-only AI keys')
            return
        if self.path != '/v1/chat/completions':
            self.send_error(404)
            return
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        model = request['model']
        MODELS.append(model)
        if model not in ('verify-agent', 'verify-translator'):
            self.send_error(400, 'Only verification model IDs are accepted')
            return
        messages = request['messages']
        tools = [message for message in messages if message.get('role') == 'tool']
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        def chunk(delta, finish=None):
            payload = {'id':'verify-'+uuid.uuid4().hex, 'object':'chat.completion.chunk',
                       'created':int(time.time()), 'model':model,
                       'choices':[{'index':0,'delta':delta,'finish_reason':finish}]}
            self.wfile.write(('data: '+json.dumps(payload)+'\n\n').encode())
            self.wfile.flush()
        if model == 'verify-agent' and not tools and request.get('tools'):
            offered = {item['function']['name'] for item in request['tools']}
            assert 'list_courses' in offered, offered
            chunk({'role':'assistant','tool_calls':[{'index':0,'id':'call_verify_courses',
                  'type':'function','function':{'name':'list_courses','arguments':'{}'}}]})
            chunk({}, 'tool_calls')
        else:
            if model == 'verify-agent':
                TOOL_ROUNDS.extend(tools)
                assert tools and 'VERIFY101' in str(tools), 'Expected actual course tool result'
                result = '已读取你的 VERIFY101 课程。'
            else:
                assert not request.get('tools'), 'Translation must not use agent tools'
                assert 'VERIFY_SOURCE_TEXT' in str(messages), 'Expected uploaded source document'
                result = '验证译文：这是一份上传的学习资料。'
            chunk({'role':'assistant','content':result[:8]})
            chunk({'content':result[8:]})
            chunk({}, 'stop')
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()

class Client:
    def __init__(self, base_url):
        self.base = base_url.rstrip('/') + '/api/v1'
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies), urllib.request.ProxyHandler({}))

    def raw(self, method, path, payload=None, body=None, headers=None, expected=(200,)):
        headers = dict(headers or {})
        if method not in ('GET','HEAD'):
            self.raw('GET','/auth/me', expected=(200,401))
            token = next((c.value for c in self.cookies if c.name == 'XSRF-TOKEN'), None)
            if token:
                headers['X-XSRF-TOKEN'] = urllib.parse.unquote(token)
        if payload is not None:
            body = json.dumps(payload).encode()
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(self.base+path, data=body, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=180)
        except urllib.error.HTTPError as error:
            response = error
        data = response.read()
        assert response.status in expected, f'{method} {path}: HTTP {response.status}: {data[:600].decode(errors="replace")}'
        return response.status, dict(response.headers), data

    def json(self, method, path, payload=None, expected=(200,)):
        _, _, data = self.raw(method,path,payload=payload,expected=expected)
        return json.loads(data) if data else None

    def upload(self, file, **fields):
        boundary = 'campus-'+uuid.uuid4().hex
        parts = []
        for key,value in fields.items():
            if value is not None:
                parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file.name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+file.read_bytes()+b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        _, _, data = self.raw('POST','/documents',body=b''.join(parts),headers={'Content-Type':f'multipart/form-data; boundary={boundary}'})
        return json.loads(data)

    def stream(self, path, payload):
        _, headers, data = self.raw('POST',path,payload=payload)
        assert headers.get('Content-Type','').startswith('text/event-stream'), headers
        events = []
        for block in data.decode().replace('\r\n','\n').split('\n\n'):
            event = None
            values = []
            for line in block.splitlines():
                if line.startswith('event:'):
                    event = line[6:].strip()
                elif line.startswith('data:'):
                    values.append(line[5:].strip())
            if values:
                events.append((event,json.loads('\n'.join(values))))
        assert not [v for k,v in events if k == 'error'], events
        assert any(k == 'delta' for k,_ in events) and any(k == 'done' for k,_ in events), events
        return events


def page_count(path, python):
    code = 'from pypdf import PdfReader; import sys; print(len(PdfReader(sys.argv[1]).pages))'
    return int(subprocess.check_output([python,'-c',code,str(path)], text=True).strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://localhost:8081')
    parser.add_argument('--fixture-port',type=int,default=8899)
    parser.add_argument('--python',default=BUNDLED_PYTHON)
    parser.add_argument('--fixtures',default='.run/fixtures')
    parser.add_argument('--storage-root',default='backend/data/files')
    args = parser.parse_args()
    fixtures = Path(args.fixtures)
    if not (fixtures/'sample.docx').exists():
        subprocess.run([args.python,'scripts/generate_fixtures.py','--output',str(fixtures)],check=True)
    server = ThreadingHTTPServer(('127.0.0.1',args.fixture_port),ModelFixture)
    thread = threading.Thread(target=server.serve_forever,daemon=True)
    thread.start()
    users = []
    checks = []
    def passed(name):
        checks.append(name)
        print('PASS '+name,flush=True)
    manifest = Path('.run/verification-users.json')
    manifest.parent.mkdir(exist_ok=True)
    previous_users = json.loads(manifest.read_text()) if manifest.exists() else []
    try:
        client = Client(args.base_url)
        other = Client(args.base_url)
        password = 'verification-only-'+uuid.uuid4().hex
        for session in (client,other):
            email = 'verify-'+uuid.uuid4().hex+'@example.test'
            user = session.json('POST','/auth/register',{'email':email,'password':password,'displayName':'验证账号'})
            users.append({'id':user['id'],'email':email})
            manifest.write_text(json.dumps(previous_users+users,indent=2))
        first_id = users[0]['id']
        updated = client.json('PATCH','/auth/me',{'displayName':'验证同学','preferences':{
            'school':'CUHK-Shenzhen','college':'验证书院','major':'计算机','studentNumber':'VERIFY001','bio':'API verification','avatarColor':'#D6E0CF',
            'aiMode':'API_KEY','aiBaseUrl':f'http://127.0.0.1:{args.fixture_port}/v1','aiModel':'verify-agent',
            'translationAiMode':'API_KEY','translationAiBaseUrl':f'http://127.0.0.1:{args.fixture_port}/v1','translationAiModel':'verify-translator'}})
        assert updated['preferences']['major'] == '计算机'
        client.json('POST','/auth/logout',expected=(204,))
        client.json('GET','/auth/me',expected=(401,))
        client.json('POST','/auth/login',{'email':users[0]['email'],'password':password})
        assert client.json('GET','/auth/me')['id'] == first_id
        passed('registration / profile / logout / session login')

        course = client.json('POST','/courses',{'code':'VERIFY101','name':'验证课程','term':'2026 Term 1'})
        client.json('PATCH','/courses/'+course['id'],{'customName':'课程测试','color':'#D6E0CF'})
        assert client.json('GET','/courses?term=2026%20Term%201')[0]['customName'] == '课程测试'
        other.json('GET','/courses/'+course['id'],expected=(404,))
        note = client.json('POST',f'/courses/{course["id"]}/notes',{'title':'验证笔记','markdown':'# Lesson'})
        client.json('PATCH','/notes/'+note['id'],{'markdown':'# Updated'})
        assert client.json('GET',f'/courses/{course["id"]}/notes')[0]['markdown'] == '# Updated'
        client.json('DELETE','/notes/'+note['id'])
        assert not client.json('GET',f'/courses/{course["id"]}/notes')
        passed('course filters / note CRUD / ownership')

        folder = client.json('POST','/document-folders',{'name':'验证目录','parentId':None})
        child = client.json('POST','/document-folders',{'name':'子目录','parentId':folder['id']})
        empty = client.json('POST','/document-folders',{'name':'空目录','parentId':child['id']})
        detail = client.json('GET','/document-folders/'+child['id'])
        assert [x['id'] for x in detail['ancestors']] == [folder['id']]
        client.json('PATCH','/document-folders/'+child['id'],{'name':'学习资料','parentId':None})
        assert client.json('GET','/document-folders/'+child['id'])['parentId'] is None
        client.json('PATCH','/document-folders/'+child['id'],{'parentId':folder['id']})
        client.json('PATCH','/document-folders/'+folder['id'],{'parentId':child['id']},expected=(400,))
        other.json('GET','/document-folders/'+child['id'],expected=(404,))
        other.json('POST','/document-folders',{'name':'非法父目录','parentId':folder['id']},expected=(404,))
        passed('folder hierarchy / breadcrumbs / rename / move / cycle / ownership')

        text = client.upload(fixtures/'notes.txt',folderId=child['id'],courseId=course['id'])
        assert text['folderId'] == child['id'] and text['createdAt'] and text['updatedAt']
        assert client.json('GET','/documents?folderId='+child['id'])[0]['id'] == text['id']
        assert not client.json('GET','/documents?folderId=root')
        client.json('PATCH','/documents/'+text['id'],{'displayName':'学习笔记.txt','folderId':None})
        assert client.json('GET','/documents?folderId=root')[0]['id'] == text['id']
        client.json('PATCH','/documents/'+text['id'],{'folderId':child['id']})
        assert 'VERIFY_SOURCE_TEXT' in client.json('GET','/documents/'+text['id']+'/text')['content']
        assert client.raw('GET','/documents/'+text['id']+'/file?download=true')[2] == (fixtures/'notes.txt').read_bytes()
        other.json('GET','/documents/'+text['id'],expected=(404,))
        other.raw('POST','/documents/download',payload={'ids':[text['id']]},expected=(404,))
        _, _, archive = client.raw('POST','/documents/download',payload={'ids':[],'folderIds':[folder['id']]})
        with zipfile.ZipFile(io.BytesIO(archive)) as z:
            assert z.testzip() is None
            assert '验证目录/学习资料/空目录/' in z.namelist(),z.namelist()
            assert z.read('验证目录/学习资料/学习笔记.txt') == (fixtures/'notes.txt').read_bytes()
        passed('upload / document move / metadata / text / download / recursive ZIP')

        originals = []
        for name in ('sample.pdf','sample.docx','sample.pptx'):
            document = client.upload(fixtures/name,folderId=child['id'])
            originals.append(document)
            _, headers, pdf = client.raw('GET','/documents/'+document['id']+'/preview')
            assert headers.get('Content-Type','').startswith('application/pdf') and pdf.startswith(b'%PDF-')
            rendered = fixtures/(name+'.preview.pdf')
            rendered.write_bytes(pdf)
            assert page_count(rendered,args.python) == 2, name
            assert client.raw('GET','/documents/'+document['id']+'/preview')[2] == pdf
        passed('real two-page PDF / DOCX / PPTX original-layout preview and reuse')

        conversation = client.json('POST','/conversations',{'title':'验证持久对话'})
        path = '/conversations/'+conversation['id']+'/messages'
        result = client.stream(path,{'content':'VERIFY_TOOL_COURSES 请列出我的课程','contextRefs':[]})
        assert 'VERIFY101' in ''.join(value.get('content','') for event,value in result if event == 'delta')
        history = client.json('GET',path)
        assert history['total'] == 2 and [m['role'] for m in history['items']] == ['USER','ASSISTANT']
        assert history['items'][1]['toolCalls'],history
        count_before = client.json('GET','/conversations')['total']
        translated = client.stream('/documents/'+text['id']+'/translate',{'targetLanguage':'中文'})
        assert '验证译文' in ''.join(v.get('content','') for e,v in translated if e == 'delta')
        assert client.json('GET','/conversations')['total'] == count_before
        assert client.json('GET',path)['total'] == 2
        assert 'verify-agent' in MODELS and 'verify-translator' in MODELS and TOOL_ROUNDS
        other.json('GET','/conversations/'+conversation['id'],expected=(404,))
        client.json('POST','/auth/logout',expected=(204,))
        client.json('POST','/auth/login',{'email':users[0]['email'],'password':password})
        assert client.json('GET',path)['total'] == 2
        passed('AI SSE fixture / actual course tool / separate translation model / persistent history')

        activity = client.json('POST','/tasks',{'title':'验证活动','taskType':'ACTIVITY','startsAt':'2027-01-10T09:00:00Z','endsAt':'2027-01-10T10:00:00Z','location':'测试地点'})
        event = client.json('POST','/tasks/'+activity['id']+'/calendar-event',{})
        assert event['eventType'] == 'ACTIVITY'
        assert client.json('POST','/tasks/'+activity['id']+'/calendar-event',{})['id'] == event['id']
        client.json('PATCH','/tasks/'+activity['id'],{'status':'COMPLETED','starred':True})
        assert client.json('GET','/tasks?status=COMPLETED&starred=true')[0]['id'] == activity['id']
        rule = client.json('POST',f'/courses/{course["id"]}/meeting-rules',{'weekday':1,'startsTime':'10:00','endsTime':'11:00','validFrom':'2027-01-04','validTo':'2027-01-11','location':'教室'})
        calendar = client.json('GET','/calendar?from=2027-01-04T00:00:00Z&to=2027-01-12T00:00:00Z')
        assert len([e for e in calendar if e.get('meetingRuleId') == rule['id']]) == 2
        client.json('DELETE','/meeting-rules/'+rule['id'])
        assert not [e for e in client.json('GET','/calendar') if e.get('meetingRuleId') == rule['id']]
        study = client.json('POST','/tasks',{'title':'验证学习','estimatedMinutes':120,'dueAt':'2027-02-01T16:00:00Z'})
        occupied = client.json('POST','/calendar/events',{'title':'已有安排','startsAt':'2027-02-01T09:30:00Z','endsAt':'2027-02-01T10:30:00Z'})
        planned = client.json('POST','/calendar/study-plan',{'taskIds':[study['id']],'from':'2027-02-01T09:00:00Z','to':'2027-02-01T13:00:00Z'})
        def instant(value):
            from datetime import datetime
            return datetime.fromisoformat(value.replace('Z','+00:00'))
        assert sum((instant(e['endsAt'])-instant(e['startsAt'])).total_seconds()/60 for e in planned) == 120
        for e in planned:
            start,end = instant(e['startsAt']),instant(e['endsAt'])
            assert end <= instant(occupied['startsAt']) or start >= instant(occupied['endsAt'])
            assert (end-start).total_seconds() <= 50*60
        for a,b in zip(planned,planned[1:]):
            assert (instant(b['startsAt'])-instant(a['endsAt'])).total_seconds() >= 600
        other.json('PATCH','/tasks/'+study['id'],{'title':'非法修改'},expected=(404,))
        dashboard = client.json('GET','/dashboard?date=2027-02-01')
        assert {'courses','tasks','events','mail','announcements'} <= dashboard.keys()
        passed('activity calendar / class generation / study capacity and breaks / dashboard')

        files = [text]+originals
        storage = Path(args.storage_root)
        for document in files:
            assert (storage/document['storageKey']).exists(), f'Check --storage-root: {storage}'
        client.json('DELETE','/document-folders/'+folder['id'])
        for document in files:
            client.json('GET','/documents/'+document['id'],expected=(404,))
            assert not (storage/document['storageKey']).exists()
            assert not (storage/'previews'/document['id']).exists()
        client.json('GET','/document-folders/'+child['id'],expected=(404,))
        for e in planned+[occupied,event]:
            client.json('DELETE','/calendar/events/'+e['id'])
        for task in (activity,study):
            client.json('DELETE','/tasks/'+task['id'])
        client.json('DELETE','/conversations/'+conversation['id'])
        passed('recursive database + physical source/preview deletion and resource cleanup')
        report = {'passed':checks,'users':users,'fixtureModels':MODELS,'toolRounds':len(TOOL_ROUNDS),
                  'note':'PostgreSQL and application HTTP verified; model provider is an explicit local test fixture, not real-provider acceptance.'}
        Path('.run/verification-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(f'PASS {len(checks)} groups; test user IDs saved to {manifest}',flush=True)
    finally:
        server.shutdown()
        server.server_close()

if __name__ == '__main__':
    main()
