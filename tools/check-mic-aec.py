#!/usr/bin/env python3
"""Live device lifecycle check; briefly restarts capture, does not test acoustic quality.
Usage: adb forward tcp:18900 tcp:8900; python3 tools/check-mic-aec.py
"""
import argparse
import json
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base', default='http://127.0.0.1:18900')
args = parser.parse_args()
url = args.base.rstrip('/') + '/api/voice/mic'


def request(path='', body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url + path, data=data,
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=8) as response:
        return json.load(response)


before = request()
try:
    for rate, aec in [(16000, False), (16000, True), (8000, True),
                      (16000, True), (48000, False), (16000, True)]:
        state = request('/start', {'rate': rate, 'aec': aec})
        assert state['on'] and state['rate'] == rate, state
        assert state['aec_requested'] == aec, state
        if not aec:
            assert not state['aec_enabled'], state
        elif not state['aec_enabled']:
            assert state['aec_error'], state  # Unsupported hardware must explain fallback.
        with urllib.request.urlopen(url + '/stream', timeout=8) as stream:
            pcm = stream.read(rate)  # 0.5 seconds of mono PCM16
            assert len(pcm) == rate, len(pcm)
        print(json.dumps(state), flush=True)
    stopped = request('', {'on': False, 'aec': False})
    assert not stopped['on'] and not stopped['aec_enabled'], stopped
    assert not stopped['aec_requested'], stopped
    print('PASS: capture, AEC toggling, sample-rate changes, stop')
finally:
    # Restore request/rate and explicit ownership; an implicit stream remains automatic.
    request('/start', {'rate': before['rate'], 'aec': before['aec_requested']})
    if not before['explicit']:
        request('/stop', {})
