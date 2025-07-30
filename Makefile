init:
	git submodule update --init
	cd depends/fpnew && git submodule update --init

format: init
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.reformat
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.test.reformat

fix: init
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.fix
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.test.fix

run: init format fix
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.runMain  ogpu.core.ALURTL

test: init format fix
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.test

ztest: init format fix
	nix --experimental-features 'nix-command flakes' develop -c mill -i ogpu.test -z $(MYOPT)

clean:
	nix --experimental-features 'nix-command flakes' develop -c mill -i clean

dev:
	nix --experimental-features 'nix-command flakes' develop -c zsh
.phony: test
