"""Strict OSV semantic-version matching; unknown input must remain unresolved."""
import re

def maven_query(line):
    parts=line.strip().split(' -- ',1)[0].split(':')
    if len(parts)>=5 and parts[-1] in ('compile','runtime'):
        return {'package':{'ecosystem':'Maven','name':':'.join(parts[:2])},'version':parts[-2]}
    return None

def version(value):
    match=re.fullmatch(r'v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([\w.-]+))?(?:\+[\w.-]+)?',value)
    if not match:raise ValueError('Unsupported semantic version: '+value)
    major,minor,patch,pre=match.groups()
    suffix=(1,) if pre is None else (0,tuple((0,int(p)) if p.isdigit() else (1,p) for p in pre.split('.')))
    return int(major),int(minor),int(patch or 0),suffix

def affected(value,ranges):
    current=version(value)
    for interval in ranges:
        if interval['type']!='SEMVER':raise ValueError('Unsupported OSV range type')
        active=False
        for event in interval['events']:
            if 'introduced' in event:active=event['introduced']=='0' or current>=version(event['introduced'])
            elif 'fixed' in event or 'limit' in event:
                if active and current<version(event.get('fixed',event.get('limit'))):return True
                active=False
            elif 'last_affected' in event:
                if active and current<=version(event['last_affected']):return True
                active=False
            else:raise ValueError('Unsupported OSV event')
        if active:return True
    return False

def github_affected(value,constraint):
    current=version(value)
    for alternative in constraint.split('||'):
        predicates=[]
        for part in alternative.split(','):
            match=re.fullmatch(r'\s*(>=|<=|>|<|=)?\s*(v?[\w.+-]+)\s*',part)
            if not match:raise ValueError('Unsupported GitHub range')
            operator,bound=match.groups();bound=version(bound)
            predicates.append({'>=':current>=bound,'<=':current<=bound,'>':current>bound,'<':current<bound,'=':current==bound,None:current==bound}[operator])
        if all(predicates):return True
    return False
