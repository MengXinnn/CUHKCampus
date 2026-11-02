#!/usr/bin/env python3
"""Verify a selected BB resource against the application's stored projection.

Reads the application's encrypted BLACKBOARD connection. Prints counts and IDs,
never credentials, personal account fields, course bodies or file names.
Requires Python cryptography, curl, and the project's running PostgreSQL service.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.parse
import uuid

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

BASE = 'https://bb.cuhk.edu.cn'
ATTACHMENT_HANDLERS = {'resource/x-bb-file', 'resource/x-bb-document', 'resource/x-bb-assignment'}


def settings():
    return dict(line.split('=', 1) for line in Path('.env').read_text().splitlines()
                if line and not line.startswith('#') and '=' in line)


def database(query, config):
    command = ['docker', 'compose', '--env-file', '.env', '-f', 'deploy/compose.yml',
               'exec', '-T', 'postgres', 'psql', '-U', config.get('CAMPUS_DATABASE_USER', 'campus'),
               '-d', 'cuhkcampus', '-t', '-A', '-q', '-v', 'ON_ERROR_STOP=1']
    result = subprocess.run(command, input=query, text=True, capture_output=True, check=True)
    text = result.stdout.strip()
    return json.loads(text) if text else None


class Blackboard:
    def __init__(self, cookie):
        self.cookie = cookie
        self.requests = 0

    def object(self, path):
        url = urllib.parse.urljoin(BASE, path)
        if urllib.parse.urlsplit(url).netloc != urllib.parse.urlsplit(BASE).netloc:
            raise ValueError('BB API returned a different origin')
        config = 'url = ' + json.dumps(url) + '\nheader = ' + json.dumps('Cookie: ' + self.cookie) + '\n'
        response = subprocess.run(['curl', '-sS', '--max-time', '60', '--config', '-',
                                   '-w', '\n%{http_code}'], input=config, text=True,
                                  capture_output=True, check=True)
        self.requests += 1
        body, _, status = response.stdout.rpartition('\n')
        if status != '200':
            raise RuntimeError(f'BB API HTTP {status} at {urllib.parse.urlsplit(url).path}')
        return json.loads(body)

    def list(self, path):
        result = []
        while path:
            value = self.object(path)
            result.extend(value['results'])
            path = (value.get('paging') or {}).get('nextPage')
        return result


def remote_inventory(api, args):
    memberships = api.list('/learn/api/public/v1/users/me/courses?limit=100')
    children = {member['childCourseId'] for member in memberships if member.get('childCourseId')}
    course_ids = list(dict.fromkeys(member['courseId'] for member in memberships if member['courseId'] not in children))
    if args.course_id:
        if args.course_id not in course_ids:
            raise ValueError('Selected course is not in this account')
        course_ids = [args.course_id]
    result = {}
    for cid in course_ids:
        contents, attachments, announcements = [], [], []
        if args.course_id:
            path = '/learn/api/public/v1/courses/' + cid + '/contents'
            if args.content_id:
                # Only the selected item's metadata and attachment list.
                item = api.object(path + '/' + args.content_id)
                contents = [item]
                if item.get('contentHandler', {}).get('id') in ATTACHMENT_HANDLERS:
                    for attachment in api.list(path + '/' + args.content_id + '/attachments?limit=100'):
                        attachments.append({'nodeId': args.content_id, 'attachmentId': attachment['id']})
            else:
                if args.parent_id:
                    path += '/' + args.parent_id + '/children'
                contents = [item for item in api.list(path + '?limit=100')
                            if item.get('availability', {}).get('available') != 'No']
            if args.announcements:
                announcements = api.list('/learn/api/public/v1/courses/' + cid + '/announcements?limit=100')
        result[cid] = {'contentIds': [item['id'] for item in contents],
                       'announcementIds': [item['id'] for item in announcements],
                       'attachments': attachments}
    return memberships, result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--user-id', required=True, type=uuid.UUID)
    parser.add_argument('--storage-root', default='data/files')
    parser.add_argument('--output', default='.run/blackboard-verification.json')
    parser.add_argument('--remote-only', action='store_true')
    parser.add_argument('--course-id', help='BB external course ID; only this course is inspected')
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument('--parent-id', help='BB folder ID; inspect immediate children only')
    scope.add_argument('--content-id', help='BB content ID; inspect its attachments only')
    parser.add_argument('--announcements', action='store_true')
    args = parser.parse_args()
    if (args.parent_id or args.content_id or args.announcements) and not args.course_id:
        parser.error('Resource selection requires --course-id')
    config = settings()
    uid = str(args.user_id)
    encrypted = database("select to_json(credentials->>'encrypted') from external_connections where user_id='" + uid + "'::uuid and provider='BLACKBOARD';", config)
    # psql selects json text only for structured results; the ciphertext query is
    # wrapped as JSON so it never needs to be emitted by this script.
    if encrypted is None:
        raise RuntimeError('This user has no connected Blackboard session')
    iv, content = encrypted.split('.', 1)
    credentials = json.loads(AESGCM(base64.b64decode(config['CAMPUS_ENCRYPTION_KEY'])).decrypt(
        base64.b64decode(iv), base64.b64decode(content), None))
    api = Blackboard(credentials['cookieHeader'])
    memberships, remote = remote_inventory(api, args)
    report = {'mode': 'remote-only' if args.remote_only else 'database-and-files', 'memberships': len(memberships), 'courses': len(remote),
              'contents': sum(len(v['contentIds']) for v in remote.values()),
              'announcements': sum(len(v['announcementIds']) for v in remote.values()),
              'attachments': sum(len(v['attachments']) for v in remote.values()),
              'apiRequests': api.requests, 'missing': [], 'fileErrors': []}
    if not args.remote_only:
        sql = """select json_build_object(
            'courses',(select coalesce(json_agg(json_build_object('id',id,'externalId',external_id)),'[]'::json) from courses where user_id='%s'::uuid and external_id is not null),
            'contents',(select coalesce(json_agg(json_build_object('id',n.id,'externalId',n.external_id,'courseId',c.external_id)),'[]'::json) from content_nodes n join courses c on c.id=n.course_id where c.user_id='%s'::uuid),
            'announcements',(select coalesce(json_agg(json_build_object('externalId',a.external_id,'courseId',c.external_id)),'[]'::json) from announcements a join courses c on c.id=a.course_id where c.user_id='%s'::uuid),
            'documents',(select coalesce(json_agg(json_build_object('externalId',d.external_id,'contentId',n.external_id,'courseId',c.external_id,'storageKey',d.storage_key,'byteSize',d.byte_size,'sha256',d.sha256)),'[]'::json) from documents d left join content_nodes n on n.id=d.content_node_id left join courses c on c.id=d.course_id where d.user_id='%s'::uuid and d.origin='BLACKBOARD'));
        """ % (uid, uid, uid, uid)
        local = database(sql, config)
        local_courses = {item['externalId'] for item in local['courses']}
        local_contents = {(item['courseId'], item['externalId']) for item in local['contents']}
        local_announcements = {(item['courseId'], item['externalId']) for item in local['announcements']}
        for cid, course in remote.items():
            if cid not in local_courses:
                report['missing'].append({'courseId': cid, 'type': 'course'})
            for node in course['contentIds']:
                if (cid, node) not in local_contents:
                    report['missing'].append({'courseId': cid, 'type': 'content', 'id': node})
            for announcement in course['announcementIds']:
                if (cid, announcement) not in local_announcements:
                    report['missing'].append({'courseId': cid, 'type': 'announcement', 'id': announcement})
            for attachment in course['attachments']:
                external_id = '/learn/api/public/v1/courses/' + cid + '/contents/' + attachment['nodeId'] + '/attachments/' + attachment['attachmentId']
                matches = [item for item in local['documents'] if item['courseId'] == cid and item['contentId'] == attachment['nodeId'] and item['externalId'] == external_id]
                if not matches:
                    report['missing'].append({'courseId': cid, 'type': 'attachment', 'id': attachment['attachmentId']})
        verified = 0
        unrequested = 0
        for item in local['documents']:
            if item['storageKey'] is None:
                unrequested += 1
                continue
            path = Path(args.storage_root) / item['storageKey']
            if not path.is_file():
                report['fileErrors'].append({'id': item['externalId'], 'error': 'missing file'})
                continue
            digest = hashlib.sha256()
            with path.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            if path.stat().st_size != item['byteSize'] or digest.hexdigest() != item['sha256']:
                report['fileErrors'].append({'id': item['externalId'], 'error': 'size/hash mismatch'})
            else:
                verified += 1
        report['unrequestedFiles'] = unrequested
        report['storedDocuments'] = len(local['documents'])
        report['verifiedPhysicalFiles'] = verified
    report['passed'] = not report['missing'] and not report['fileErrors']
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({key: value for key, value in report.items() if key not in ('missing', 'fileErrors')}, ensure_ascii=False), flush=True)
    if not report['passed']:
        raise SystemExit('BB verification found missing or inconsistent data; inspect the local report.')


if __name__ == '__main__':
    main()
