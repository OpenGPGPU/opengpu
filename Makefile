init:
	git submodule update --init

resolve: init format
	nix --experimental-features 'nix-command flakes' develop -c mill -i resolve _

format: init
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.reformat
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.test.reformat

run: init format
	nix --experimental-features 'nix-command flakes' develop -c	mill -i ogpu.runMain  ogpu.rtl.ALURTL

test: init format
	nix --experimental-features 'nix-command flakes' develop -c	mill -i ogpu.test

clean:
	nix --experimental-features 'nix-command flakes' develop -c	mill -i clean
.phony: test
