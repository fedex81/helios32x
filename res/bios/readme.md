The emulator uses the provided homebrew bioses by default.

If you'd like to uses official bioses you'll have to set the following flag on startup:
-D32x.use.homebrew.bios=false
and put the following files in this folder; filenames must match what is shown below.

|fileName|sha1 checksum|desc|
|---|---|---|
|32x_bios_m.bin|1e5b0b2441a4979b6966d942b20cc76c413b8c5e|SH2 Master BIOS|
|32x_bios_s.bin|4103668c1bbd66c5e24558e73d4f3f92061a109a|SH2 Slave BIOS|
|32x_bios_g.bin|dbebd76a448447cb6e524ac3cb0fd19fc065d944|68K BIOS|