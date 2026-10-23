#!/usr/bin/env python3
"""Track every recovered RPC without confusing generated models with support."""
import argparse
import csv
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IMPLEMENTED = {
    'bale.auth.v1.Auth': {
        'StartPhoneAuth', 'ValidateCode', 'ValidatePassword', 'SignUp', 'GetJWTToken',
        'GetAuthSessions', 'TerminateSession', 'TerminateAllSessions', 'SignOut',
    },
    'bale.messaging.v2.Messaging': {
        'LoadDialogs', 'LoadHistory', 'SendMessage', 'MessageRead', 'UpdateMessage',
        'DeleteMessage', 'ForwardMessages', 'PinMessage', 'UnPinMessages',
        'LoadPinnedMessages', 'ClearChat', 'DeleteChat', 'PinDialogs', 'UnpinDialogs',
        'ArchiveDialogs', 'UnArchiveDialogs',
    },
    'bale.abacus.v1.Abacus': {'MessageSetReaction', 'MessageRemoveReaction'},
    'bale.search.v1.Search': {'SearchMessages'},
    'bale.presence.v1.Presence': {'Typing', 'StopTyping'},
    'ai.bale.server.Files': {'GetNasimFileUploadUrl', 'GetNasimFileUrl'},
    'bale.users.v1.Users': {
        'LoadUsers', 'GetContacts', 'SearchContacts', 'AddContact', 'RemoveContact',
        'BlockUser', 'UnblockUser', 'GetFullUser', 'EditName', 'EditNickName',
        'EditAbout', 'EditAvatar',
    },
    'bale.groups.v1.Groups': {
        'LoadGroups', 'GetFullGroup', 'LoadMembers', 'CreateGroup', 'EditGroupTitle',
        'EditGroupAbout', 'GetGroupInviteURL', 'RevokeInviteURL', 'JoinGroup',
        'JoinPublicGroup', 'LeaveGroup', 'InviteUser', 'KickUser', 'MakeUserAdmin',
        'RemoveUserAdmin',
    },
    'bale.poll.v1.Poll': {'CreatePoll', 'Vote', 'ClosePoll', 'GetPollResults'},
    'bale.story.v1.Story': {'GetAllStories', 'AddStory', 'ReactToStory', 'RemoveStory'},
}
LIVE_VERIFIED = {
    'bale.auth.v1.Auth': {'StartPhoneAuth', 'ValidateCode', 'ValidatePassword', 'SignUp', 'GetJWTToken'},
    'bale.messaging.v2.Messaging': {'LoadDialogs', 'LoadHistory', 'SendMessage', 'MessageRead'},
}
# Deferred per the user's messenger-first decision; not a claim of API viability.
MESSENGER = {
    'bale.auth.v1.Auth', 'bale.messaging.v2.Messaging', 'bale.users.v1.Users',
    'bale.groups.v1.Groups', 'bale.v1.Files', 'ai.bale.server.Files', 'bale.v1.Images',
    'bale.search.v1.Search', 'bale.presence.v1.Presence', 'bale.abacus.v1.Abacus',
    'bale.story.v1.Story', 'bale.shared_media.v1.SharedMediaService', 'ai.bale.pushak.Push',
    'bale.poll.v1.Poll', 'bale.report.v1.Report',
}


def render():
    schema = json.loads((ROOT / 'reference/protocol/schema.json').read_text())
    text = io.StringIO(newline='')
    writer = csv.writer(text, lineterminator='\n')
    writer.writerow(['service', 'method', 'request', 'response', 'scope', 'implementation', 'android_build', 'live_verified'])
    for service, methods in sorted(schema['services'].items()):
        for method, types in sorted(methods.items()):
            impl = method in IMPLEMENTED.get(service, set())
            live = method in LIVE_VERIFIED.get(service, set())
            writer.writerow([
                service, method, types['request'], types['response'],
                'messenger' if service in MESSENGER else 'needs-triage-or-deferred',
                'source-implemented' if impl else 'not-implemented',
                'passed-ci' if impl else 'models-only',
                'user-baseline' if live else 'no',
            ])
    return text.getvalue()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    path = ROOT / 'docs/rpc-coverage.csv'
    if args.check:
        assert path.read_text() == render(), 'Run python3 tools/generate_coverage.py'
    else:
        path.write_text(render())
    print(f'RPC coverage inventory is up to date (699 RPCs; {sum(map(len, IMPLEMENTED.values()))} source implementations).')
