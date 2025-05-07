init:
	git submodule update --init

format: init
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.reformat
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.test.reformat

run: init format
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.runMain  ogpu.rtl.ALURTL

test: init format
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.test

ztest: init format
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.test -z $(MYOPT)

clean:
	nix --experimental-features 'nix-command flakes' develop -c mill -i clean

dev:
	nix --experimental-features 'nix-command flakes' develop -c zsh
.phony: test
