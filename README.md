# helios32x

An EXPERIMENTAL Sega 32x emulator, written in Java.

Most of the emulator infrastructure is borrowed from the [helios](https://github.com/fedex81/helios) project.

# How to Run
Requires java 17+ installed.

Get the most recent zip file from the download section,
for example `helios32x-21.1101.zip` and extract to a folder.

## Windows
Double click on `launcher.bat`

## Linux
Open a terminal and run:
`chmod +x launcher.sh`
`./lanucher.sh`

## BIOSes
Three bios files are required and they must be stored in the `./res/bios` folder,
filenames must match what is shown below.

|fileName|sha1 checksum|desc|
|---|---|---|
|32x_bios_m.bin|1e5b0b2441a4979b6966d942b20cc76c413b8c5e|SH2 Master BIOS|
|32x_bios_s.bin|4103668c1bbd66c5e24558e73d4f3f92061a109a|SH2 Slave BIOS|
|32x_bios_g.bin|dbebd76a448447cb6e524ac3cb0fd19fc065d944|68K BIOS|

# Credits

## Code

Hitachi SH2 cpu emulator adapted from [esoteric_emu](https://github.com/fedex81/esoteric_emu)'s
SH4 implementation

For additional credits, see the [helios](https://github.com/fedex81/helios/blob/master/CREDITS.md) project.
