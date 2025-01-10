init:
	git submodule update --init

resolve: init format
	nix --experimental-features 'nix-command flakes' develop -c mill -i resolve _

format:
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.reformat

run: init format
	nix --experimental-features 'nix-command flakes' develop -c	mill -i ogpu.runMain  ogpu.rtl.ALURTL

format_test:
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.test.reformat

test: init format_test format
	nix --experimental-features 'nix-command flakes' develop -c	mill -i ogpu.test

clean:
	nix --experimental-features 'nix-command flakes' develop -c	mill -i clean
.phony: test
