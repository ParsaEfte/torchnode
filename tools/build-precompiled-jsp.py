#!/usr/bin/env python3
"""Build a separate opt-in JSP-precompiled JAR. No application/database startup.
ECJ and Ant are build tools only; the original JAR and recovery bindings are never overwritten.
"""
import argparse,hashlib,json,os,subprocess,tarfile,xml.etree.ElementTree as ET,zipfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('jar',type=Path);p.add_argument('output',type=Path)
p.add_argument('--expected-jar-sha256',required=True);p.add_argument('--jdk',type=Path,required=True)
p.add_argument('--ant',type=Path,required=True);p.add_argument('--ant-launcher',type=Path,required=True)
a=p.parse_args();repo=Path(__file__).resolve().parents[1];root=a.output.resolve()
if root.exists():raise ValueError('Output collision; original artifacts must not be replaced')
with a.jar.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
if digest!=a.expected_jar_sha256:raise ValueError('Input JAR binding mismatch')
os.umask(0o077);root.mkdir(parents=True,mode=0o700);web=root/'webapp';web.mkdir();generated=root/'generated';generated.mkdir();classes=root/'classes';classes.mkdir()
env={k:v for k,v in os.environ.items() if not k.startswith(('TORCHNODE_','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'))}
classpath=os.pathsep.join(str(x.resolve()) for x in [a.jar,a.ant,a.ant_launcher])
with zipfile.ZipFile(a.jar) as source:
 for name in source.namelist():
  if not name.startswith('webapp/') or name.endswith('/'):continue
  relative=Path(name.removeprefix('webapp/'))
  if relative.is_absolute() or '..' in relative.parts:raise ValueError('Unsafe web resource')
  target=web/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read(name));os.utime(target,(1794528000,1794528000))
 with (root/'jspc.log').open('x') as log:
  subprocess.run([str(a.jdk/'bin/java'),'-cp',classpath,'org.apache.jasper.JspC','-uriroot',str(web),'-d',str(generated),'-webinc',str(root/'mappings.xml'),'-source','21','-target','21','-failFast'],env=env,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=90)
 document=ET.fromstring('<web-app>'+ (root/'mappings.xml').read_text().replace('<?xml version="1.0" encoding="UTF-8"?>','')+'</web-app>')
 servlets={x.findtext('servlet-name'):x.findtext('servlet-class') for x in document.findall('servlet')}
 mappings={x.findtext('url-pattern'):servlets[x.findtext('servlet-name')] for x in document.findall('servlet-mapping')}
 expected={'/WEB-INF/views/'+name+'.jsp' for name in ['dashboard','inspection','analytics','report','layout','admin','workspace','topology']}
 if set(mappings)!=expected:raise ValueError('Incomplete/extra precompiled view mappings')
 javafiles=sorted(generated.rglob('*.java'))+[repo/'src/main/java/io/github/gavinruff007/torchnode/dashboard/DashboardServer.java',repo/'src/main/java/io/github/gavinruff007/torchnode/dashboard/PrecompiledViews.java']
 with (root/'javac.log').open('x') as log:
  subprocess.run([str(a.jdk/'bin/javac'),'--release','21','-encoding','UTF-8','-cp',classpath,'-d',str(classes),*[str(x) for x in javafiles]],env=env,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=90)
 replacements={str(x.relative_to(classes)):x.read_bytes() for x in classes.rglob('*.class')}
 replacements['torchnode-precompiled-jsp.properties']=''.join(k+'='+v+'\n' for k,v in sorted(mappings.items())).encode()
 excluded=[];payload={}
 for name in source.namelist():
  if name.endswith('/'):continue
  if name.startswith(('org/eclipse/jdt/','META-INF/maven/org.eclipse.jdt/ecj/')) or name.endswith('.jsp'):
   excluded.append(name);continue
  data=source.read(name)
  if name.startswith('META-INF/services/'):
   lines=data.decode().splitlines();kept=[line for line in lines if 'org.eclipse.jdt.' not in line]
   if kept!=lines:
    excluded.append(name)
    if not kept:continue
    data=('\n'.join(kept)+'\n').encode()
  payload[name]=data
 payload.update(replacements)
 if not any(name.startswith('org/eclipse/jdt/') for name in excluded):raise ValueError('ECJ removal not demonstrated')
 artifact=root/'torchnode-precompiled.jar'
 with zipfile.ZipFile(artifact,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as target:
  for name,data in sorted(payload.items()):
   info=zipfile.ZipInfo(name,(2026,11,13,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;target.writestr(info,data)
report={'inputJarSha256':digest,'jar':str(artifact),'sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'views':mappings,'ecjEntriesRemoved':len([n for n in excluded if n.startswith('org/eclipse/jdt/')]),'jspSourcesRemoved':len([n for n in excluded if n.endswith('.jsp')]),'runtimeEcjPresent':any(n.startswith('org/eclipse/jdt/') for n in payload),'buildToolInputs':{str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in [a.ant,a.ant_launcher]},'legalApproval':False,'recoveryArtifactReplaced':False}
(root/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
