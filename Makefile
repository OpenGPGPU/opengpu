init:
	git submodule update --init

resolve: init format
	nix --experimental-features 'nix-command flakes' develop -c mill -i resolve _

format:
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.reformat

.phony: test
