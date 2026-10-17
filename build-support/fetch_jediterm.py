"""Fetch pinned public artifacts when Java's HTTP client cannot follow the vendor CDN.

Uses normal TLS verification and checks the vendor's published digest before writing.
All other dependencies are resolved by Maven. No user credentials are accessed.
"""
import argparse
import hashlib
import os
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', required=True, type=Path)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    pom = ET.parse(project / 'pom.xml')
    version = pom.findtext('{*}properties/{*}jediterm.version')
    if not version or not all(c.isdigit() or c == '.' for c in version):
        raise ValueError('Invalid JediTerm version in pom.xml')
    for artifact in ('jediterm-core', 'jediterm-ui'):
        destination = args.repo / 'org/jetbrains/jediterm' / artifact / version
        for suffix in ('.pom', '.jar'):
            name = f'{artifact}-{version}{suffix}'
            target = destination / name
            if target.exists():
                continue
            url = f'https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/jediterm/{artifact}/{version}/{name}'
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            with urllib.request.urlopen(url + '.sha1', timeout=30) as response:
                expected = response.read().decode('ascii').strip().split()[0]
            if hashlib.sha1(data).hexdigest() != expected:
                raise ValueError(f'Digest mismatch: {name}')
            destination.mkdir(parents=True, exist_ok=True)
            temporary = target.with_name(name + '.download')
            try:
                temporary.write_bytes(data)
                os.replace(temporary, target)
            finally:
                temporary.unlink(missing_ok=True)
            print(f'Verified official artifact: {name}')


if __name__ == '__main__':
    main()
