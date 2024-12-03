final: prev: {
  mill = prev.mill.overrideAttrs (oldAttrs: rec {
    version = "0.12.3";
    src = prev.fetchurl {
      url = "https://github.com/com-lihaoyi/mill/releases/download/${version}/${version}-assembly";
      sha256 = "sha256-hqzAuYadCciYPs/b6zloLUfrWF4rRtlBSMxSj7tLg7g=";
    };
  });
}
