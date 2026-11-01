ori $t0, $zero, 0x8001
lui $t1, 0x8001
lui $t2, 0xffff
ori $t2, $t2, 0xffff
ori $t3, $zero, 0xffff
lui $t3, 0
ori $zero, $zero, 0xffff
lui $zero, 0xffff
.expect reg $t0 0x00008001
.expect reg $t1 0x80010000
.expect reg $t2 0xffffffff
.expect reg $t3 0
.expect reg $zero 0
