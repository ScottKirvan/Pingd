# Contributing to Ping'd

First off, thank you for considering contributing to Ping'd! It's people like you that make this project better for everyone.

## Code of Conduct

This project and everyone participating in it is governed by our [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How Can I Contribute?

### Reporting Bugs

Before creating bug reports, please check the existing issues to avoid duplicates. When you create a bug report, include as many details as possible using our bug report template.

**Guidelines for bug reports:**
- Use a clear and descriptive title
- Describe the exact steps to reproduce the problem
- Provide specific examples to demonstrate the steps
- Describe the behavior you observed and what you expected to see
- Include screenshots if applicable
- Note your environment (OS, version, etc.)

### Suggesting Enhancements

Enhancement suggestions are tracked as GitHub issues. When creating an enhancement suggestion, use our feature request template and include:

- A clear and descriptive title
- A detailed description of the proposed feature
- Examples of how the feature would be used
- Why this enhancement would be useful

### Pull Requests

**Before submitting a pull request:**

1. Fork the repository and create your branch from `dev` (the ongoing development branch — see [Branches](README.md#branches))
2. If you've added code, add tests if applicable
3. Ensure your code follows the existing style
4. Make sure your commits follow our commit message conventions
5. Update documentation as needed

**Commit Message Convention:**

We use [Conventional Commits](https://www.conventionalcommits.org/) with [Semantic Versioning](https://semver.org/):

- `feat:` - New features (bumps MINOR version)
- `fix:` - Bug fixes (bumps PATCH version)
- `feat!:` or `fix!:` - Breaking changes (bumps MAJOR version)
- `docs:` - Documentation only changes
- `style:` - Code style changes (formatting, etc.)
- `refactor:` - Code refactoring
- `test:` - Adding or updating tests
- `chore:` - Maintenance tasks

**Examples:**
```
feat: add cellular-only network scope support
fix: correct SSID whitelist matching against connected networks
docs: update installation instructions
fix!: change default ping target host
```

### Pull Request Process

1. Update the README.md with details of changes if applicable
2. Update the CHANGELOG.md is handled automatically by Release Please
3. The PR will be merged once you have approval from a maintainer
4. Your PR should pass all checks and have no merge conflicts

## Development Setup

1. Fork and clone the repository
2. Create a new branch for your feature/fix, off `dev`
3. Make your changes
4. Run `./gradlew build` to compile, run tests, and lint
5. Submit a pull request

## Project Structure

See the [Repo Layout](README.md#repo-layout) section in the README.

## Testing

```bash
./gradlew build   # compiles, assembles debug + release APKs, runs unit tests, lints
./gradlew test    # unit tests only
```

Requires a local Android SDK — see [Development](README.md#development) in the
README for setup. No device or emulator is required; the Compose UI tests run
on the JVM via Robolectric. `.github/workflows/android-ci.yml` runs
`./gradlew build` on every push/PR that touches the Android project.

## Questions?

Feel free to open an issue for questions or reach out via:
- [LinkedIn](https://www.linkedin.com/in/scottkirvan/)
- [Discord](https://discord.gg/TN6XJSNK5Y)

Thank you for your contributions!
