import zipfile, re, sys

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
print('entries:', z.namelist()[:20])
for n in z.namelist():
    if not n.endswith('.dex'):
        continue
    d = z.read(n)
    print(n, len(d), 'magic', d[:8])
    for probe in [b'TvPipController', b'TvPipBoundsState', b'TvPipBoundsAlgorithm',
                  b'PipBoundsAlgorithm', b'PipBoundsState', b'TvPipController']:
        print('   ', probe.decode(), d.find(probe))
