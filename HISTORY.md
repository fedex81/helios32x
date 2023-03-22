## 23.0322
32x: rewrite reg handling
32x: pwm, support l/r channel mapping
32x: pwm, fix init value for the interrupt counter
32x: savestate handling, update lib
32x: sh2 should read rom via md mapper (if any)
32x: add framebuffer mirror to sh2 memory map
32x: add z80 delays when accessing sh2 side
32x: add debug mem view
32x: when mdVdp is set as H32, stretch to H40 to match s32x
32x: fix RLE, draw all pixels
32x: fix direct color mode in NTSC V28
32x: fbcr, int_mask write fixes, improve handling of illegal insts
32x: only check FM bit on reads when assertions are enabled
helios: migrate Size enum to use ints
sh2: fix sci protocol + fix for mars check sci
sh2: drc fix trapa, trapa is a branch instruction
sh2: fix sci for mars check, add test
sh2: run DmaC when polling is active
sh2: let illegal inst exception propagate
sh2: handle word-writes to CCR
sh2: warn on overlapping interrupts
sh2: dma autoReq shouldn't stop a dma dreq in progress
sh2: fix bugs on polling, activation

## 22.1206
* default to DRC, cacheOn
* does not require official bioses, uses homebrew
* lots of other changes, some games are playable

## 22.0516-PD-SNAPSHOT
* (pre-drc branch)
* 32x: fix bug on address masking
* 32x: fix sh reg handling
* 32x: fix NTSC_H40_V30 mode
* 32x: sh2 prefetch fixes, sh2 timing fixes, enable FM
* 32x: tweak sh2 cycles
* sh2: fix ADDC, rewrite a few insts
* sh2: only autoclear one type of interrupts

## 22.0515-DRC-SNAPSHOT
* 32x: add support for unofficial bioses
* 32x: fix dirty block detection, perf tweak
* 32x: fix bug on address masking
* 32x: fix NTSC_H40_V30 mode
* 32x: fix sh reg handling
* 32x: new Sh2 prefetcher
* sh2: add objectweb asm library, initial drc code
* sh2: fix SLEEP, should not increment PC
* sh2: fix MOVWI, MOVLI when in a delayed branch
* sh2: fix ADDC
* sh2: fix STSMPR cycles
* sh2: only autoclear one type of interrupts

## 22.0315-SNAPSHOT
* implement sh2 cache
* add FM, RV bit handling
* fix joypad detection

## 22.0223-SNAPSHOT
* more stuff working, lots broken still

## 21.1214-SNAPSHOT
* first public release, not much working yet!