import { defineConfig } from 'vitepress'

export default defineConfig({
  title: "ScooterGitTemplate",
  description: "TODO: Replace with your project description.",
  base: '/ScooterGitTemplate/',
  themeConfig: {
    nav: [
      { text: 'Home', link: '/' },
      { text: 'GitHub', link: 'https://github.com/ScottKirvan/ScooterGitTemplate' }
    ],
    socialLinks: [
      { icon: 'github', link: 'https://github.com/ScottKirvan/ScooterGitTemplate' },
      { icon: 'discord', link: 'https://discord.gg/TN6XJSNK5Y' }
    ],
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © Scott Kirvan'
    }
  }
})
