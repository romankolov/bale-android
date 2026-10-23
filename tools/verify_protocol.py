#!/usr/bin/env python3
"""Validate every recovered field, RPC type, and wire envelope without Android.
Run inside a venv with tools/requirements.txt installed.
"""
import importlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROTO = ROOT / 'core/protocol/src/main/proto'
SCHEMA = json.loads((ROOT / 'reference/protocol/schema.json').read_text())


def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out + bytes([value]))


def scalar(number, value):
    return varint(number << 3) + varint(value)


def blob(number, value):
    return varint((number << 3) | 2) + varint(len(value)) + value


class ProtocolTest(unittest.TestCase):
    def test_every_runtime_message_and_field(self):
        from google.protobuf.descriptor import FieldDescriptor as F
        scalar_types = {name: getattr(F, 'TYPE_' + name.upper()) for name in (
            'int32', 'int64', 'uint32', 'uint64', 'sint32', 'sint64', 'fixed32',
            'fixed64', 'sfixed32', 'sfixed64', 'float', 'double', 'bool', 'string', 'bytes')}
        for name, fields in SCHEMA['messages'].items():
            if name.startswith('google.protobuf.'):
                continue
            message = bale.DESCRIPTOR.message_types_by_name[name]
            self.assertEqual(len(fields), len(message.fields), name)
            for number, (field_name, kind, repeated) in fields.items():
                field = message.fields_by_number[int(number)]
                self.assertEqual(repeated, field.is_repeated, (name, field_name))
                if kind in scalar_types:
                    self.assertEqual(scalar_types[kind], field.type, (name, field_name))
                else:
                    expected = kind if kind.startswith('google.protobuf.') else 'bale.recovered.' + kind
                    self.assertEqual(expected, field.message_type.full_name, (name, field_name))

    def test_every_rpc_references_existing_types(self):
        for service, methods in SCHEMA['services'].items():
            self.assertGreaterEqual(service.count('.'), 2)
            for method, types in methods.items():
                for role, name in types.items():
                    if name is not None:
                        self.assertIn(name, SCHEMA['messages'], (service, method, role))

    def test_handshake_matches_reference_encoder(self):
        actual = transport.SocketRequest(handshake_request=transport.Handshake(mkproto_version=1, api_version=176652))
        self.assertEqual(blob(3, scalar(1, 1) + scalar(2, 176652)), actual.SerializeToString())

    def test_request_is_direct_not_extra_wrapped(self):
        payload = b'\x08\x2a'
        request_id = (1 << 61) + 123  # Must never pass through Double/JavaScript Number.
        request = transport.SocketRequest(request=transport.RpcRequest(
            service='bale.messaging.v2.Messaging', method='SendMessage', payload=payload,
            request_id=request_id))
        body = blob(1, b'bale.messaging.v2.Messaging') + blob(2, b'SendMessage') + blob(3, payload) + scalar(5, request_id)
        self.assertEqual(blob(1, body), request.SerializeToString())
        self.assertEqual(request_id, transport.SocketRequest.FromString(request.SerializeToString()).request.request_id)

    def test_metadata_layout_matches_reference(self):
        entry = transport.MetadataEntry(name='app_version', value=transport.MetadataValue(text='176652'))
        metadata = transport.Metadata(entries=[entry])
        expected = blob(1, blob(1, b'app_version') + blob(2, blob(1, b'176652')))
        self.assertEqual(expected, metadata.SerializeToString())

    def test_response_error_and_correlation(self):
        raw = blob(1, blob(1, scalar(1, 403) + blob(2, b'forbidden')) + scalar(3, 99))
        response = transport.SocketResponse.FromString(raw)
        self.assertEqual(99, response.response.number)
        self.assertEqual(403, response.response.error.topic)
        self.assertEqual('forbidden', response.response.error.message)

    def test_empty_session_termination_preserves_presence(self):
        response = transport.SocketResponse.FromString(b'\x1a\x00')
        self.assertTrue(response.HasField('terminate_session'))
        self.assertFalse(transport.SocketResponse().HasField('terminate_session'))

    def test_update_envelope_and_high_field_numbers(self):
        update = bale.Update.FromString(blob(52815, b''))
        self.assertTrue(update.HasField('messages'))
        wrapped = transport.SocketResponse.FromString(blob(2, blob(1, update.SerializeToString())))
        self.assertEqual(update.SerializeToString(), wrapped.update.update)

    def test_unicode_text_and_stable_message_id(self):
        rid = (1 << 62) - 1
        message = bale.Messaging_SendMessage_Request(rid=rid,
            peer=bale.Peer2(type=1, id=123, accessHash=987654321),
            message=bale.Message2(textMessage=bale.TextMessage(text='سلام 👋 hello')))
        decoded = bale.Messaging_SendMessage_Request.FromString(message.SerializeToString())
        self.assertEqual(rid, decoded.rid)
        self.assertEqual('سلام 👋 hello', decoded.message.textMessage.text)

    def test_unknown_fields_survive_roundtrip(self):
        raw = blob(55555, b'future')
        self.assertEqual(raw, bale.Message2.FromString(raw).SerializeToString())


if __name__ == '__main__':
    import grpc_tools
    subprocess.run([sys.executable, str(ROOT / 'tools/generate_protocol.py'), '--check'], check=True)
    with tempfile.TemporaryDirectory() as generated:
        subprocess.run([sys.executable, '-m', 'grpc_tools.protoc', '-I' + str(PROTO),
                        '-I' + str(Path(grpc_tools.__file__).parent / '_proto'),
                        '--python_out=' + generated, *map(str, sorted(PROTO.glob('*.proto')))], check=True)
        sys.path.insert(0, generated)
        bale = importlib.import_module('bale_pb2')
        transport = importlib.import_module('transport_pb2')
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(ProtocolTest)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        sys.exit(not result.wasSuccessful())
