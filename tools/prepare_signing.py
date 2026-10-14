"""Create or restore the single, private PDFCRAFT_SIGNING_BUNDLE GitHub secret.

Never print the bundle/passwords. Only its public certificate fingerprint is committed.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys


def certificate(store, password, alias):
    env = dict(os.environ, PDFCRAFT_KEYTOOL_PASSWORD=password)
    result = subprocess.run([
        'keytool', '-exportcert', '-keystore', str(store), '-storetype', 'PKCS12',
        '-storepass:env', 'PDFCRAFT_KEYTOOL_PASSWORD', '-alias', alias,
    ], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise ValueError('Cannot unlock the signing certificate; check the signing bundle.')
    return hashlib.sha256(result.stdout).hexdigest()


def create(directory):
    # Never replace a previously generated key, even when a command is rerun.
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    password = secrets.token_urlsafe(36)
    store = directory / 'PDFCraft-signing.p12'
    env = dict(os.environ, PDFCRAFT_KEYTOOL_PASSWORD=password)
    subprocess.run([
        'keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', str(store),
        '-storepass:env', 'PDFCRAFT_KEYTOOL_PASSWORD',
        '-keypass:env', 'PDFCRAFT_KEYTOOL_PASSWORD', '-alias', 'pdfcraft',
        '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=PDFCraft Android, OU=App signing',
    ], env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    store.chmod(0o600)
    fingerprint = certificate(store, password, 'pdfcraft')
    bundle = dict(version=1, keystore=base64.b64encode(store.read_bytes()).decode(),
                  store_password=password, key_password=password, alias='pdfcraft')
    secret = directory / 'github-secret.txt'
    secret.write_text(base64.b64encode(json.dumps(bundle).encode()).decode(), encoding='ascii')
    secret.chmod(0o600)
    (directory / 'certificate.sha256').write_text(fingerprint + '\n', encoding='ascii')
    print('Private signing backup created. Do not commit or share its contents.')
    print('Public certificate SHA-256:', fingerprint)


def restore(expected_file, output, github_env):
    encoded = os.environ.get('PDFCRAFT_SIGNING_BUNDLE', '').strip()
    if not encoded:
        raise ValueError('Add the PDFCRAFT_SIGNING_BUNDLE repository secret first. No replacement key will be generated.')
    try:
        bundle = json.loads(base64.b64decode(encoded, validate=True))
        if not isinstance(bundle, dict) or bundle.get('version') != 1:
            raise ValueError()
        values = [bundle[name] for name in ('store_password', 'key_password', 'alias')]
        if any(not isinstance(value, str) or not value or '\n' in value or '\r' in value for value in values):
            raise ValueError()
        key_bytes = base64.b64decode(bundle['keystore'], validate=True)
        if not key_bytes:
            raise ValueError()
    except (ValueError, TypeError, KeyError):
        raise ValueError('Invalid signing bundle; use the complete generated value.') from None
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Ephemeral CI output only; the durable private backup is never overwritten.
    with output.open('xb') as handle:
        handle.write(key_bytes)
    output.chmod(0o600)
    try:
        fingerprint = certificate(output, bundle['store_password'], bundle['alias'])
        if fingerprint != expected_file.read_text().strip().lower():
            raise ValueError('Signing certificate does not match this project. Refusing an incompatible APK.')
        env_values = dict(PDFCRAFT_SIGNING_STORE_FILE=str(output.resolve()),
                          PDFCRAFT_SIGNING_STORE_PASSWORD=bundle['store_password'],
                          PDFCRAFT_SIGNING_KEY_PASSWORD=bundle['key_password'],
                          PDFCRAFT_SIGNING_ALIAS=bundle['alias'])
        if github_env:
            for value in values:
                # Mask decoded values too: GitHub only masks the outer secret automatically.
                print('::add-mask::' + value.replace('%', '%25'))
            with Path(github_env).open('a') as handle:
                for name, value in env_values.items():
                    handle.write(f'{name}={value}\n')
        else:
            raise ValueError('GITHUB_ENV must be set when restoring the CI signing key.')
        print('Fixed signing certificate verified:', fingerprint)
    except Exception:
        output.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--create', type=Path)
    group.add_argument('--restore', type=Path)
    parser.add_argument('--expected', type=Path, default=Path('config/signing-certificate.sha256'))
    args = parser.parse_args()
    try:
        if args.create:
            create(args.create)
        else:
            restore(args.expected, args.restore, os.environ.get('GITHUB_ENV'))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Do not print subprocess arguments, bundle data or decoded passwords.
        print('Signing preparation failed: ' + (str(error) if isinstance(error, ValueError) else 'Check the private key files and Java installation.'), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
