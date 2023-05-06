This project is now read only after merging with the [helios](https://github.com/fedex81/helios) project.  
Please refer to helios [releases](https://github.com/fedex81/helios/releases) for an up to date build of the emulator.  
Historical helios32x releases will remain available [here](https://github.com/fedex81/helios32x/releases).

# helios32x

A Sega 32x emulator, written in Java.  
Most of the emulator infrastructure is borrowed from the [helios](https://github.com/fedex81/helios) project.

# Performance

The target is a modern mobile cpu capable of boosting ~4Ghz (ie. [AMD Ryzen 5 PRO 5650U](https://www.amd.com/en/products/apu/amd-ryzen-5-pro-5650u)), this should allow perf close to 60fps for most titles, YMMV.


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

# Credits

## Code

Hitachi SH2 cpu emulator adapted from [esoteric_emu](https://github.com/fedex81/esoteric_emu)'s
SH4 implementation

For additional credits, see the [helios](https://github.com/fedex81/helios/blob/master/CREDITS.md) project.
