#!/usr/bin/env python3
"""Create and verify a private, consistent source backup before integration work.

The source is read from reachai.env; both host and schema must be confirmed on the
command line. The password is sent to the dump process through WSLENV, not argv.
"""
import argparse
import datetime
import hashlib
import os
import pathlib
import re
import subprocess
import sys

import pymysql


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--expect-host', required=True)
    parser.add_argument('--expect-port', required=True, type=int)
    parser.add_argument('--expect-database', required=True)
    parser.add_argument('--output-root', required=True)
    parser.add_argument('--wsl-distro', required=True)
    args = parser.parse_args()
    values = {}
    for line in pathlib.Path(args.config).read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip().strip('"\'')
    host = values['AI_MYSQL_HOST']
    port = int(values.get('AI_MYSQL_PORT', '3306'))
    database = values['AI_MYSQL_DATABASE']
    user = values['AI_MYSQL_USER']
    password = values['AI_MYSQL_PASSWORD']
    if (host, port, database) != (args.expect_host, args.expect_port, args.expect_database):
        raise RuntimeError('configured source differs from explicitly expected target')
    jdbc = os.environ.get('AI_MYSQL_URL') or values.get('AI_MYSQL_URL', '')
    if jdbc:
        match = re.match(r'jdbc:mysql://([^/:?]+)(?::(\d+))?/([^?]+)', jdbc)
        if not match or (match[1], int(match[2] or '3306'), match[3]) != (host, port, database):
            raise RuntimeError('effective JDBC target differs from backup target')

    connection = pymysql.connect(host=host, port=port, user=user, password=password,
                                 database=database, charset='utf8mb4', connect_timeout=10,
                                 read_timeout=30)
    try:
        with connection.cursor() as cursor:
            cursor.execute('SELECT DATABASE(), VERSION(), @@port')
            identity = cursor.fetchone()
            if identity[0] != database or identity[2] != port:
                raise RuntimeError('connected database identity differs from target')
            cursor.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")
            table_count = cursor.fetchone()[0]
            cursor.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND engine <> 'InnoDB'")
            if cursor.fetchone()[0]:
                raise RuntimeError('single-transaction backup requires all base tables to be InnoDB')
    finally:
        connection.close()
    if not table_count:
        raise RuntimeError('source database is unexpectedly empty')

    root = pathlib.Path(args.output_root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    directory = root / ('reachai-csharp-source-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    directory.mkdir()
    if os.name == 'nt':
        username = os.environ.get('USERNAME')
        if not username:
            raise RuntimeError('cannot restrict backup directory without current username')
        subprocess.run(['icacls', str(directory), '/inheritance:r', '/grant:r', username + ':(OI)(CI)F'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    target = directory / (database + '.sql')
    env = os.environ.copy()
    env['MYSQL_PWD'] = password
    env['WSLENV'] = ':'.join(filter(None, [env.get('WSLENV', ''), 'MYSQL_PWD/u']))
    command = ['wsl.exe', '-d', args.wsl_distro, '-u', 'root', '--', 'mariadb-dump',
               '--protocol=tcp', '--host=' + host, '--port=' + str(port), '--user=' + user,
               '--single-transaction', '--skip-lock-tables', '--routines', '--triggers', '--events',
               '--no-tablespaces', '--hex-blob', '--default-character-set=utf8mb4', '--databases', database]
    try:
        with target.open('xb') as output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, env=env, check=False)
        if result.returncode:
            raise RuntimeError('database backup command failed (details suppressed)')
        content = target.read_bytes()
        actual_tables = len(re.findall(rb'CREATE TABLE `', content))
        if actual_tables != table_count or b'Dump completed' not in content:
            raise RuntimeError('backup completeness verification failed')
        digest = hashlib.sha256(content).hexdigest()
        (directory / 'sha256.txt').write_text(digest + '  ' + target.name + '\n', encoding='ascii')
        print('backup_directory=' + str(directory))
        print('database_version=' + str(identity[1]))
        print('base_tables=' + str(table_count))
        print('backup_bytes=' + str(len(content)))
        print('sha256=' + digest)
    except Exception:
        target.unlink(missing_ok=True)
        raise


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('backup_failed=' + type(exc).__name__ + ': ' + str(exc), file=sys.stderr)
        sys.exit(1)
