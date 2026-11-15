#!/usr/bin/env python3
"""Loopback-only checks of probe timeout/redirect/status and form-cookie contracts."""
import base64,http.server,os,struct,subprocess,tempfile,threading
from pathlib import Path

repo=Path(__file__).resolve().parents[1]
class Handler(http.server.BaseHTTPRequestHandler):
 status=200
 requests=[]
 def log_message(self,*args):pass
 def do_GET(self):
  self.requests.append((self.path,self.headers.get('Cookie'),''))
  self.send_response(self.status)
  if self.status==302:self.send_header('Location','http://192.0.2.1/never-follow')
  self.end_headers();self.wfile.write(b'bounded-response')
 def do_POST(self):
  body=self.rfile.read(int(self.headers.get('Content-Length','0'))).decode()
  self.requests.append((self.path,self.headers.get('Cookie'),body))
  self.send_response(302);self.send_header('Set-Cookie','session=fixture; HttpOnly');self.end_headers()

with tempfile.TemporaryDirectory(prefix='torchnode-http-probes-') as temporary:
 root=Path(temporary)
 subprocess.run(['javac','--release','21','-d',str(root),str(repo/'tools/ContainerHttpProbe.java'),str(repo/'src/main/java/io/github/gavinruff007/torchnode/HealthCheck.java')],check=True)
 server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
 assert server.server_port!=8080
 thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
 env={k:v for k,v in os.environ.items() if not k.startswith(('TORCHNODE_','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'))}
 env['TORCHNODE_PORT']=str(server.server_port)
 try:
  for status in (200,302,500):
   Handler.status=status;before=len(Handler.requests)
   result=subprocess.run(['java','-cp',str(root),'io.github.gavinruff007.torchnode.HealthCheck'],env=env,capture_output=True,timeout=6)
   assert result.returncode==(0 if status==200 else 1)
   assert len(Handler.requests)==before+1,'Redirect followed'
  before=len(Handler.requests);bad=dict(env,TORCHNODE_PORT='80')
  assert subprocess.run(['java','-cp',str(root),'io.github.gavinruff007.torchnode.HealthCheck'],env=bad,capture_output=True,timeout=6).returncode==1
  assert len(Handler.requests)==before
  def call(fields):
   data=b''.join(struct.pack('>I',len(s.encode()))+s.encode() for s in fields)
   return subprocess.run(['java','-cp',str(root),'ContainerHttpProbe'],input=data,env=env,capture_output=True,timeout=6)
  result=call(['/login','POST','csrf=fixture&password=a%2Bb','session=prior'])
  assert result.returncode==0,result.stderr
  lines=result.stdout.decode().splitlines();assert lines[0]=='302'
  assert b'session=fixture' in base64.b64decode(lines[1])
  assert Handler.requests[-1]==('/login','session=prior','csrf=fixture&password=a%2Bb')
  before=len(Handler.requests);assert call(['//192.0.2.1/','GET','','']).returncode!=0
  assert len(Handler.requests)==before
 finally:server.shutdown();server.server_close();thread.join()
print('PASS: status/redirect/invalid-port handling, cookie/form transport and foreign-target rejection')
