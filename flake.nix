{
  description = "opengpu environment";



  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }@inputs:
    let
      overlay = import ./overlay.nix;
    in
    flake-utils.lib.eachDefaultSystem
      (system:
      let
        pkgs = import nixpkgs { inherit system; overlays = [ overlay ]; };
        deps = with pkgs; [
          git
          gnumake autoconf automake
          mill
          dtc
          verilator cmake ninja
          circt
        ];
      in
        {
          legacyPackages = pkgs;
          devShell = pkgs.mkShell {
            buildInputs = deps;
            shellHook = ''
              echo "Hello opengpu world!"
            '';
          };
        }
      )
    // { inherit inputs; overlays.default = overlay; };
}
