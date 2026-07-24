# ScooterGitTemplate [![starline](https://starlines.qoo.monster/assets/ScottKirvan/ScooterGitTemplate)](https://github.com/qoomon/starline)
<div align="center">

  <img src="assets/media/logo.jpg" alt="logo" width="200" height="auto" />
    <h1><a href="https://github.com/ScottKirvan/ScooterGitTemplate">ScottKirvan/ScooterGitTemplate</a></h1>
  <h3>Nulla nobis dicta iste minus dolor repellendus aspernatur atque</h3>
  
  
<!-- Badges -->
<p>
  <a href="https://github.com/ScottKirvan/ScooterGitTemplate/graphs/contributors">
    <img src="https://img.shields.io/github/contributors/ScottKirvan/ScooterGitTemplate" alt="contributors" />
  </a>
  <a href="">
    <img src="https://img.shields.io/github/last-commit/ScottKirvan/ScooterGitTemplate" alt="last update" />
  </a>
  <a href="https://github.com/ScottKirvan/ScooterGitTemplate/network/members">
    <img src="https://img.shields.io/github/forks/ScottKirvan/ScooterGitTemplate" alt="forks" />
  </a>
  <a href="https://github.com/ScottKirvan/ScooterGitTemplate/stargazers">
    <img src="https://img.shields.io/github/stars/ScottKirvan/ScooterGitTemplate" alt="stars" />
  </a>
  <a href="https://github.com/ScottKirvan/ScooterGitTemplate/issues/">
    <img src="https://img.shields.io/github/issues/ScottKirvan/ScooterGitTemplate" alt="open issues" />
  </a>
  <a href="https://github.com/ScottKirvan/ScooterGitTemplate/blob/main/LICENSE.md">
    <img src="https://img.shields.io/github/license/ScottKirvan/ScooterGitTemplate.svg" alt="license" />
  </a>
  <a href="https://discord.gg/gQH4mXWQRT">
    <!--<img src="https://img.shields.io/discord/704680098577514527?style=flat-square&label=%F0%9F%92%AC%20discord&color=00ACD7">-->
    <img src="https://img.shields.io/discord/1052011377415438346?style=flat-square&label=discord&color=00ACD7">
  </a>
</p>
   
<h4>
    <a href="https://tinyurl.com/3vf7whyd">View Demo</a>
  <span> · </span>
    <a href="https://github.com/ScottKirvan/ScooterGitTemplate/blob/main/README.md">Documentation</a>
  <span> · </span>
    <a href="https://github.com/ScottKirvan/ScooterGitTemplate/issues/new?template=bug_report.md">Report Bug</a>
  <span> · </span>
    <a href="https://github.com/ScottKirvan/ScooterGitTemplate/issues/new?template=feature_request.md">Request Feature</a>
  </h4>
</div>

**ScooterGitTemplate** is voluptatibus magni nemo est. Nulla nobis dicta iste minus dolor repellendus aspernatur atque. Earum expedita aut inventore tempora fugiat deleniti. Molestias minima nam expedita beatae totam ipsa reprehenderit animi. Occaecati quibusdam beatae ducimus voluptate ut doloribus vitae amet. Quia ut ut voluptate dignissimos adipisci dolorum rem.

## Getting Started with This Template

>[!IMPORTANT]
> **Customization Checklist** - After creating a repository from this template, customize these items:
>
> - [ ] Update the project description (line 5 above and in repository settings)
> - [ ] Replace `assets/media/logo.jpg` with your project logo
> - [ ] Update or remove the "View Demo" link (line 35)
> - [ ] Update or remove the Discord badge/link (lines 28-31)
> - [ ] Choose and apply a `.gitignore` from `.github/gitignore-templates/` (see [gitignore templates](.github/gitignore-templates/))
> - [ ] Update the version in `.release-please-manifest.json` to your starting version (e.g., "0.1.0")
> - [ ] Fill in the Features, Installation, and Usage sections below
> - [ ] Review and update the [Code of Conduct](CODE_OF_CONDUCT.md) contact information
> - [ ] Enable GitHub Pages in repository settings if you want a project website
> - [ ] Remove or update this checklist section

Branches
--------
`main` is the [deployed](https://www.scottkirvan.com/ScooterGitTemplate/) branch.  The repo doesn't currently contain any other historic or dev branches.

Repo Layout
-----------
```
ScooterGitTemplate
├───_layouts                     # Jekyll layouts for GitHub Pages
├───.github
│   ├───gitignore-templates      # Example .gitignore files (Unreal, Unity, Python, etc.)
│   ├───ISSUE_TEMPLATE           # Bug report and feature request templates
│   ├───release-please           # Release-Please configuration
│   ├───workflows                # GitHub Actions (release, template-init)
│   ├───FUNDING.yml              # Sponsorship configuration
│   └───PULL_REQUEST_TEMPLATE.md # PR template
├───assets
│   ├───css                      # Styling for GitHub Pages
│   └───media                    # Images and logos
├───notes                        # CHANGELOG, VERSION, TODO
├───CODE_OF_CONDUCT.md           # Community guidelines
├───CONTRIBUTING.md              # Contribution guidelines
├───LICENSE.md                   # MIT License
└───README.md                    # This file
```

### Key Features

**GitHub Pages Support**: The `_layouts` and `assets/css` folders enable GitHub Pages rendering with a custom dark theme similar to GitHub's [Dark High Contrast](https://github.blog/changelog/2021-08-25-dark-high-contrast-theme-ga/) theme. Enable Pages in your repo settings - see [GitHub's Jekyll documentation](https://docs.github.com/en/pages/setting-up-a-github-pages-site-with-jekyll).

**Automated Release Management**: The `.github/workflows` folder includes [Release-Please](https://github.com/googleapis/release-please) for automated versioning and CHANGELOG updates based on conventional commits.

**Template Initialization**: The `template-init.yml` workflow automatically updates repository references when you create a new repo from this template, then deletes itself.

**.gitignore Templates**: The `.github/gitignore-templates/` folder contains ready-to-use `.gitignore` files for Unreal Engine, Unity, Python, Node.js, C++, and general development. See the [templates README](.github/gitignore-templates/) for usage.

>[!NOTE]
> When using this template project, do not clone the tags or branches. Stick with `main` as the name of your main release branch. Change the version number in the `.release-please-manifest.json` file to the version you want to start with.
>
> Release-Please uses  [Conventional Commits](https://www.conventionalcommits.org/) with [Semantic Versioning](https://semver.org/) (version: MAJOR.MINOR.PATCH). Changes to version numbers are triggered by specific keywords in your commit messages:
> - `feat:` (new feature) will bump the MINOR version number.
> - `fix:` (bug fixes) will bump the PATCH number.
> - `feat!:` `fix!:` or any `xxx!:` (major and breaking changes) will bump the MAJOR version number.

>[!TIP]
> **Automatic Template Initialization**: When you create a new repository from this template, a GitHub Actions workflow automatically runs on your first push to update all repository references, URLs, and badges in the README with your new repository information. The workflow then deletes itself to keep your repo clean. No manual setup required!



Table of Contents
-----------------
- [Branches](#branches)
- [Repo Layout](#repo-layout)
- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [Contributions / Contact](#contributions--contact)
- [Credits](#credits)

Features
--------
Installation
------------
Usage
-----

Contributions / Contact
-----------------------
- Please [file an issue](https://github.com/ScottKirvan/ScooterGitTemplate/issues/new), or [grab a fork](https://github.com/ScottKirvan/ScooterGitTemplate/fork), hack away, and submit a [pull request](https://github.com/ScottKirvan/ScooterGitTemplate/pulls).
- Contact me at [linkedin.com/in/scottkirvan/](https://www.linkedin.com/in/scottkirvan/)
- You can also contact me at my [discord](https://discord.gg/TN6XJSNK5Y) server, I'm cptvideo.

Credits
-------
**[ScooterGitTemplate](https://github.com/ScottKirvan/ScooterGitTemplate) Copyright (c) (2025):** [Scott Kirvan](https://github.com/ScottKirvan)  - All rights reserved
*ScooterGitTemplate is licensed under the [MIT License](LICENSE.md).*

Project Link:  [ScooterGitTemplate](https://github.com/ScottKirvan/ScooterGitTemplate)  
[CHANGELOG](notes/CHANGELOG.md)  
[TODO](notes/TODO.md)
