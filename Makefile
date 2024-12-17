init:
	git submodule update --init

resolve: init format
	nix --experimental-features 'nix-command flakes' develop -c mill -i resolve _

format:
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.reformat

run: init format
	nix --experimental-features 'nix-command flakes' develop -c	mill -i ogpu.runMain  ogpu.rtl.ALURTL

clean:
	nix --experimental-features 'nix-command flakes' develop -c	mill -i clean
.phony: test
