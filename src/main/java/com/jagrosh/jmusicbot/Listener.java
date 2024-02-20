/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jagrosh.jmusicbot;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.menu.ButtonMenu;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Emoji;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.VoiceChannel;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.ShutdownEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.Button;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class Listener extends ListenerAdapter {
    private final Bot bot;
    private final static String SHUFFLE = "\uD83D\uDD00"; // Example shuffle emoji
    private final static String SKIP = "\u23ED"; // Example skip emoji
    private final static String CLEAR = "\uD83D\uDDD1"; // Example clear emoji
    private final static String CLOWN = "\uD83E\uDD21"; // 🤡
    Button shuffleButton =
            Button.primary("shuffle", "Shuffle").withEmoji(Emoji.fromUnicode(SHUFFLE));
    Button skipButton = Button.success("skip", "Skip").withEmoji(Emoji.fromUnicode(SKIP));
    Button clearButton = Button.danger("clear", "Clear").withEmoji(Emoji.fromUnicode(CLEAR));

    public Listener(Bot bot) {
        this.bot = bot;
    }

    @Override
    public void onReady(ReadyEvent event) {
        if (event.getJDA().getGuildCache().isEmpty()) {
            Logger log = LoggerFactory.getLogger("MusicBot");
            log.warn(
                    "This bot is not on any guilds! Use the following link to add the bot to your guilds!");
            log.warn(event.getJDA().getInviteUrl(JMusicBot.RECOMMENDED_PERMS));
        }
        credit(event.getJDA());
        event.getJDA().getGuilds().forEach((guild) -> {
            try {
                String defpl = bot.getSettingsManager().getSettings(guild).getDefaultPlaylist();
                VoiceChannel vc =
                        bot.getSettingsManager().getSettings(guild).getVoiceChannel(guild);
                if (defpl != null && vc != null
                        && bot.getPlayerManager().setUpHandler(guild).playFromDefault()) {
                    guild.getAudioManager().openAudioConnection(vc);
                }
            } catch (Exception ignore) {
            }
        });
        if (bot.getConfig().useUpdateAlerts()) {
            bot.getThreadpool().scheduleWithFixedDelay(() -> {
                try {
                    User owner =
                            bot.getJDA().retrieveUserById(bot.getConfig().getOwnerId()).complete();
                    String currentVersion = OtherUtil.getCurrentVersion();
                    String latestVersion = OtherUtil.getLatestVersion();
                    if (latestVersion != null && !currentVersion.equalsIgnoreCase(latestVersion)) {
                        String msg = String.format(OtherUtil.NEW_VERSION_AVAILABLE, currentVersion,
                                latestVersion);
                        owner.openPrivateChannel().queue(pc -> pc.sendMessage(msg).queue());
                    }
                } catch (Exception ex) {
                } // ignored
            }, 0, 24, TimeUnit.HOURS);
        }
    }

    @Override
    public void onGuildMessageDelete(GuildMessageDeleteEvent event) {
        bot.getNowplayingHandler().onMessageDelete(event.getGuild(), event.getMessageIdLong());
    }

    private void sendNewControls(ButtonClickEvent event, String content) {
        event.getChannel().sendMessage(content)
                .setActionRows(ActionRow.of(shuffleButton, skipButton, clearButton))
                .queue(message -> bot.setLastControlsMessage(event.getChannel().getIdLong(),
                        message.getIdLong()));
    }

    private void sendMessage(String content, @Nonnull ButtonClickEvent event) {
        Long channelId = event.getChannel().getIdLong();
        Long lastMessageId = bot.getLastControlsMessage(channelId);

        if (lastMessageId != null) {
            event.getChannel().retrieveMessageById(lastMessageId).queue(message -> {
                message.editMessage(content)
                        .setActionRows(ActionRow.of(shuffleButton, skipButton, clearButton))
                        .queue(null, throwable -> sendNewControls(event, content));
            }, throwable -> sendNewControls(event, content)); // If retrieval fails, send new
        } else {
            sendNewControls(event, content);
        }
    }

    @Override
    public void onButtonClick(@Nonnull ButtonClickEvent event) {
        String componentId = event.getComponentId();
        AudioHandler handler =
                (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();

        switch (componentId) {
            case "shuffle":
                int shuffleStatus = handler.getQueue().shuffle(event.getUser().getIdLong());
                switch (shuffleStatus) {
                    case 0:
                        this.sendMessage("You don't have any music in the queue to shuffle!",
                                event);
                        break;
                    case 1:
                        this.sendMessage("You only have one song in the queue!", event);
                        break;
                    default:
                        this.sendMessage(
                                "You successfully shuffled your " + shuffleStatus + " entries.",
                                event);
                        break;
                }
                break;
            case "skip":
                if (handler.getPlayer().getPlayingTrack() != null) {
                    RequestMetadata rm = handler.getRequestMetadata();
                    String skipMessage = CLOWN + " **Skipped** "
                            + handler.getPlayer().getPlayingTrack().getInfo().title + "** "
                            + (rm.getOwner() == 0L ? "(autoplay)"
                                    : "(requested by " + rm.user.username + ") **");
                    handler.getPlayer().stopTrack();

                    this.sendMessage(skipMessage, event);
                } else {
                    this.sendMessage("There is no track currently playing to skip.", event);
                }
                break;
            case "clear":
                if (handler != null) {
                    handler.stopAndClear();
                    event.getGuild().getAudioManager().closeAudioConnection();
                    this.sendMessage("The player has stopped and the queue has been cleared.",
                            event);
                } else {
                    this.sendMessage("There is no active player to stop or clear.", event);
                }
                break;
            default:
                this.sendMessage("How'd you even get here?", event);
                break;
        }

        // Always acknowledge the button click to let Discord know it was received
        event.deferEdit().queue();
        super.onButtonClick(event);
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        bot.getAloneInVoiceHandler().onVoiceUpdate(event);
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        bot.shutdown();
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        credit(event.getJDA());
    }

    // make sure people aren't adding clones to dbots
    private void credit(JDA jda) {
        Guild dbots = jda.getGuildById(110373943822540800L);
        if (dbots == null)
            return;
        if (bot.getConfig().getDBots())
            return;
        jda.getTextChannelById(119222314964353025L).sendMessage(
                "This account is running JMusicBot. Please do not list bot clones on this server, <@"
                        + bot.getConfig().getOwnerId() + ">.")
                .complete();
        dbots.leave().queue();
    }
}
