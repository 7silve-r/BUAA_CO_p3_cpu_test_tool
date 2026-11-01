# Overflow wraps; source/destination aliases and R0 protection.
lui $t0, 0x7fff
ori $t0, $t0, 0xffff
ori $t1, $zero, 1
add $t2, $t0, $t1
sub $t3, $t2, $t1
lui $t4, 0xffff
ori $t4, $t4, 0xffff
add $t5, $t4, $t1
sub $t6, $zero, $t1
add $t1, $t1, $t1
sub $t1, $t1, $t1
add $zero, $t0, $t0
sub $zero, $t4, $t0
nop
.expect reg $t2 0x80000000
.expect reg $t3 0x7fffffff
.expect reg $t5 0
.expect reg $t6 0xffffffff
.expect reg $t1 0
.expect reg $zero 0
