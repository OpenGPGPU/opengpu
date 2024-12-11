final: prev: {
  mill = prev.mill.overrideAttrs (oldAttrs: rec {
    version = "0.11.13";
    src = prev.fetchurl {
      url = "https://github.com/com-lihaoyi/mill/releases/download/${version}/${version}-assembly";
      sha256 = "sha256-hfhxIAEpl7dafW4+FGBHZfeTdQ7Dreou9r/6Kstai8A=";
    };
  });
}
