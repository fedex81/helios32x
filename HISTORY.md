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