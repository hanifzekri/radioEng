"""Build the Android wrapper using Android SDK 35 and JDK 17+ (Linux/WSL)."""
from pathlib import Path
import argparse, os, secrets, shutil, subprocess, zipfile

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--sdk', required=True, help='SDK directory containing build-tools and platforms, or extracted Google archives')
    parser.add_argument('--output', default='Radio-Mohandes-R36.apk')
    args=parser.parse_args()
    root=Path(__file__).resolve().parent
    sdk=Path(args.sdk).resolve()
    tools=sdk/'build-tools/35.0.0'
    if not tools.exists(): tools=sdk/'android-15'
    platform=sdk/'platforms/android-35/android.jar'
    if not platform.exists():platform=sdk/'android-35/android.jar'
    assert platform.is_file(), 'Android SDK platform 35 is required'
    output=Path(args.output).resolve()
    build=root/'build'; build.mkdir(exist_ok=True)
    for folder in ['generated','classes','dex']:
        path=build/folder
        if path.exists():shutil.rmtree(path)
        path.mkdir()
    def run(*cmd):subprocess.run([str(x) for x in cmd],cwd=root,check=True)
    run(tools/'aapt2','compile','--dir',root/'res','-o',build/'compiled.zip')
    run(tools/'aapt2','link','-o',build/'resources.apk','-I',platform,'--manifest',root/'AndroidManifest.xml','--java',build/'generated','--min-sdk-version','23','--target-sdk-version','35',build/'compiled.zip')
    sources=list((root/'src').rglob('*.java'))+list((build/'generated').rglob('*.java'))
    run('java','-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','--release','8','-classpath',platform,'-d',build/'classes',*sources)
    run('java','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','23','--lib',platform,'--output',build/'dex',*list((build/'classes').rglob('*.class')))
    unsigned=build/'unsigned.apk';shutil.copyfile(build/'resources.apk',unsigned)
    with zipfile.ZipFile(unsigned,'a') as z:
        for p in (build/'dex').glob('*.dex'):z.write(p,p.name,compress_type=zipfile.ZIP_STORED)
    run(tools/'zipalign','-P','16','-f','4',unsigned,build/'aligned.apk')
    signing=root/'signing';signing.mkdir(mode=0o700,exist_ok=True)
    key=signing/'radio-mohandes-online.p12';password=signing/'password.txt'
    if key.exists() != password.exists():raise RuntimeError('Signing files incomplete; restore the original pair instead of making a new key')
    if not key.exists():
        password.write_text(secrets.token_urlsafe(36)+'\n');password.chmod(0o600)
        run('java','-m','java.base/sun.security.tools.keytool.Main','-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:file',password,'-alias','radio-mohandes-online','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Radio Mohandes, O=OHGroup, C=IR')
        key.chmod(0o600)
    output.parent.mkdir(parents=True,exist_ok=True)
    run('java','-jar',tools/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','radio-mohandes-online','--ks-pass','file:'+str(password),'--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',output,build/'aligned.apk')
    run('java','-jar',tools/'lib/apksigner.jar','verify','--verbose','--print-certs',output)
    run(tools/'zipalign','-c','-P','16','4',output)
    print('Built:',output)

if __name__=='__main__':main()
