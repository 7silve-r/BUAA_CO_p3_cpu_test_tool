# Explicitly sample 12 cycles of an intentional self branch.
.steps 12
ori $t0, $zero, 7
again: beq $t0, $t0, again
.expect reg $t0 7
