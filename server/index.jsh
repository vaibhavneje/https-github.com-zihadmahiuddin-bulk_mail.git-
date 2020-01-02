const bodyParser = require("body-parser");
const cors = require("cors");
const dotenv = require("dotenv");
const express = require("express");
const nodemailer = require("nodemailer");
const cron = require("node-cron");
const firebase = require("firebase-admin");
const firebaseConfig = require("./admin_sdk_config.json");

firebase.initializeApp({
  credential: firebase.credential.cert(firebaseConfig)
});

const app = express();
dotenv.config();

const PORT = process.env.PORT || 1234;

let schedules = [];

const sendMail = async (
  host,
  port,
  secure,
  auth,
  from,
  to,
  subject,
  text,
  html
) => {
  const transporter = nodemailer.createTransport({
    host,
    port,
    secure,
    auth
  });
  const response = await transporter.sendMail({
    from,
    to,
    subject,
    text,
    html
  });
  firebase
    .firestore()
    .collection("logs")
    .add({
      time: new Date(),
      count: response.accepted.length || response.rejected.length,
      success: !!response.accepted.length
    });
  return response;
};

const cleanSchedule = obj => {
  if ("schedule" in obj) {
    delete obj.schedule;
  }
  return obj;
};

const cleanSchedules = arr => {
  return arr.map(x => cleanSchedule(x));
};

app.use(bodyParser.json());
app.use(cors());
app.use("/", express.static("build"));

app.get("/schedules", (_req, res) => {
  res.json(cleanSchedules(schedules));
});

app.get("/schedule/:index", (req, res) => {
  if (!schedules[req.params.index]) {
    return res.status(404).send();
  }
  res.json(schedules[req.params.index]);
});

app.delete("/schedule/:index", (req, res) => {
  if (!schedules[req.params.index]) {
    return res.status(404).send();
  }
  schedules[req.params.index].schedule.stop();
  schedules[req.params.index].schedule.destroy();
  schedules = schedules.filter((_x, i) => {
    console.log(`${i} === ${req.params.index}: ${i === req.params.index}`);
    return i === req.params.index;
  });
  res.status(200).json({ error: false });
});

app.get("/add", (req, res) => {
  const schedule = cron.schedule("*/6 * * * *", () => {
    console.log("YO");
  });
  schedules.push({
    schedule,
    properties: {
      time: new Date(),
      cronExpression: "*/6 * * * *"
    }
  });
  res.json(cleanSchedules(schedules));
});

app.post("/send", async (req, res) => {
  console.log(req.body);
  let response = null;
  try {
    const {
      host,
      port,
      secure,
      auth,
      from,
      to,
      subject,
      text,
      html,
      cronExpression,
      limit
    } = req.body;
    if (cronExpression) {
      if (!cron.validate(cronExpression)) {
        res.status(400).send({ error: "Invalid cron expression" });
        return;
      }
      const schedule = cron.schedule(cronExpression, async () => {
        if (!to.length) {
          schedule.stop();
          schedule.destroy();
          return;
        }
        const currentTo = to.splice(0, limit);
        const response = await sendMail(
          host,
          port,
          secure,
          auth,
          from,
          currentTo,
          subject,
          text,
          html
        );
        firebase
          .firestore()
          .collection("logs")
          .add({
            time: new Date(),
            count: response.accepted.length || response.rejected.length,
            success: !!response.accepted.length
          });
      });
      schedule.start();
      schedules.push({
        schedule,
        properties: { time: new Date(), cronExpression, limit }
      });
      res.status(200).json({ error: false });
    } else {
      response = await sendMail(
        host,
        port,
        secure,
        auth,
        from,
        to,
        subject,
        text,
        html
      );
    }
  } catch (err) {
    console.log(err);
    response = err;
  }
  if (!res.headersSent) {
    res.send(response);
  }
});

app.listen(PORT, () => {
  console.log("Server started!");
});
